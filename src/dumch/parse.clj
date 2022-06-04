(ns dumch.parse
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [dumch.util :refer [ws maps mapcats]]
    [instaparse.core :as insta :refer [defparser]]
    [rewrite-clj.node :as n 
     :refer [list-node map-node token-node keyword-node vector-node]
     :rename {list-node lnode, vector-node vnode,  map-node mnode, 
              token-node tnode, keyword-node knode}])
  (:import java.util.Base64))

(defn- dart-op->clj-op [o]
  (case o
    "is" 'dart/is?
    "??" 'or
    "==" '=
    "!=" 'not=
    "%"  'rem
    "~/" 'quot
    (symbol o)))

(defn- node->number [[tag value]]
  (if (= tag :number)
    (-> value (str/replace #"^\." "0.") read-string)
    (throw (ex-info "node with wrong tag passed" {:tag tag}))))

(defn- flatten-dot [node ast->clj]
  (loop [[tag v1 v2 :as node] node ;; having structure like [dot [dot a b] c]
         stack [] ;; put here 'c', then 'b', while trying to find 'a' ^
         [a [_ n params :as b] :as rslt] []  ;; result will look like [a b c]
         op? false]
    (cond (= :dot tag) (recur v1 (conj stack v2) rslt op?)
          (= :dot-op tag) (recur v1 (conj stack v2) rslt true)
          node (recur nil (conj stack node) rslt op?)
          (seq stack) (recur node (pop stack) (conj rslt (peek stack)) op?)

          op? (lnode (list* (tnode 'some->) ws (maps ast->clj rslt)))
          (= (count rslt) 2) 
          (ast->clj [:invoke n (cons :params (cons [:argument a] (next params)))])
          :else (lnode (list* (tnode '->) ws (maps ast->clj rslt))))))

(defn flatten-same-node [[f & params]]
  (list* 
    [:identifier (case f :and 'and, :or 'or, :add '+, :mul '*)]
    (mapcat 
      #(if (and (sequential? %) (= (first %) f))
         (next (flatten-same-node %))
         [%])
      params)))

(defn- flatten-compare [[_ & params :as and-node]]
  (let [compare-nodes (->> params (filter (fn [[f]] (= f :compare))))
        get-adjacent (fn [mapfn]
                       (->> compare-nodes
                            mapfn
                            (partition 2 1) 
                            (take-while (fn [[p1 p2]] (= (last p1) (second p2))))
                            (mapcat identity)
                            seq))
        build-ast #(list* :compare+ 
                          (vector :identifier (nth (first %) 2))
                          (distinct (mapcat (fn [[_ a _ b]] [a b]) %)))
        compare-vals (or (get-adjacent identity) (get-adjacent reverse))]
    (if compare-vals 
      (conj (filterv (fn [v] (not (some #(= v %) compare-vals))) and-node) 
            (build-ast compare-vals))
      and-node)))

(defn ast->clj [[tag v1 v2 v3 :as node]]
  #_(println :node node)
  (case tag
    :s (if v2 
         (lnode (list* (tnode 'do) ws (->> node rest (maps ast->clj)))) 
         (ast->clj v1))

    :constructor (lnode (list* (ast->clj v1) ws (ast->clj v2)))
    :params (mapcats ast->clj (rest node))
    :argument (if v2 (map ast->clj (rest node)) [(ast->clj v1)])
    :named-arg (knode (keyword (ast->clj v1)))

    :dot  (flatten-dot node ast->clj)
    :dot-op (flatten-dot node ast->clj)
    :invoke (lnode (list* (ast->clj [:field v1]) ws (ast->clj v2)))
    :field (tnode (symbol (str "." (ast->clj v1))))

    :lambda (lnode [(tnode 'fn) ws (ast->clj v1) ws (ast->clj v2)])
    :lambda-body (ast->clj v1)
    :lambda-args (vnode (->> node rest (maps ast->clj)))

    :ternary (ast->clj [:if v1 v2 v3])
    :if (case (count node)
          3 (lnode (list* (tnode 'when) ws (->> node rest (maps ast->clj))))
          4 (lnode (list* (tnode 'if) ws (->> node rest (maps ast->clj))))
          (lnode 
            (if (even? (count node))
              (concat 
                (list* (tnode 'cond) ws (->> node butlast rest (maps ast->clj)))
                [ws (knode :else) ws (ast->clj (last node))])
              (list* (tnode 'cond) ws (->> node rest (maps ast->clj)))))) 

    :const (n/meta-node (tnode :const) (ast->clj v1))
    :identifier (symbol (str/replace v1 #"!" ""))
    :list (n/vector-node (maps ast->clj (rest node))) 
    :map (mnode (maps ast->clj (rest node)))
    :get (lnode [(ast->clj v1) ws (ast->clj v2)])
    :string (str/replace v1 #"^.|.$" "") 
    :number (node->number node)

    :neg (lnode [(tnode '-) ws (ast->clj v1)])
    :sub (lnode [(tnode '-) ws (ast->clj v1) ws (ast->clj v2)])
    :await (lnode [(tnode 'await) ws (ast->clj v1)])

    :and (->> node flatten-same-node flatten-compare (maps ast->clj) lnode)
    :compare+ (lnode (list* (ast->clj v1) ws (->> node (drop 2) (maps ast->clj))))

    (cond 
      (#{:or :add :mul} tag) (lnode (maps ast->clj (flatten-same-node node)))
      (#{:return :typed-value} tag) (ast->clj v1)
      (#{:not :dec :inc} tag) (lnode [(tnode (symbol tag)) ws (ast->clj v1)])
      (#{:compare :div :ifnull :equality} tag)
      (lnode [(tnode (dart-op->clj-op v2)) ws (ast->clj v1) ws (ast->clj v3)])
      (and (keyword? tag) (-> tag str second (= \_))) :unidiomatic
      :else :unknown)))

(defparser widget-parser 
  (io/resource "widget-parser.bnf")
  :auto-whitespace :standard)

(defn- substitute-curly-quotes [s]
  (str/replace s #"\"|'" "”"))

(defn- encode [to-encode]
  (.encodeToString (Base64/getEncoder) (.getBytes to-encode)))

(defn- decode [to-decode]
  (String. (.decode (Base64/getDecoder) to-decode)))

(defn- multiline->single [s]
  (let [pattern #"'{3}([\s\S]*?'{3})|\"{3}([\s\S]*?\"{3})"
        transform (fn [[m]] (substitute-curly-quotes m))]
    (-> s
        (str/replace pattern transform)
        (str/replace #"”””" "'"))))

(defn clean
  "removes comments from string, transforms multiline string
  to singleline, replaces quotes (', \") with 'curly-quotes' (“)"
  [code]
  (let [str-pattern #"([\"\'])(?:(?=(\\?))\2.)*?\1"
        transform #(fn [[m]]
                     (str "'" (% (.substring m 1 (dec (count m)))) "'"))]
    (-> code 

        ;; to simplify modifications below
        multiline->single

        ;; TODO: find another wat to deal with comments
        ;; the problem is that comments could appear inside strings
        (str/replace str-pattern (transform encode))    ; encode strings to Base64

        ;; cleaning code from comments
        (str/replace #"\/\*(\*(?!\/)|[^*])*\*\/" "")    ; /* ... */ 
        (str/replace #"(\/\/).*" "")                    ; // ... 
        (str/replace str-pattern 
                     (transform (comp substitute-curly-quotes 
                                      decode)))))) 

(defn save-read [code]
  (try 
    (n/sexpr code)
    (catch Exception e (n/string code))))

(defn dart->ast [dart]
  (insta/parse widget-parser (clean dart)))

(defn dart->clojure [dart]
  (-> dart dart->ast (ast->clj) save-read))

(comment 

  (def code "1 < 2 && true && 2 < 3")

  (defparser widget-parser 
    (io/resource "widget-parser.bnf")
    :auto-whitespace :standard)

  (insta/parses widget-parser code)

  (dart->clojure code)

  (-> "a && b && c" 
    dart->ast 
    ast->clj 
    n/string
    ))

