# dartclojure

App to convert dart code with flutter widgets creation into
ClojureDart.

## Why it is not full dart->clojure converter

Converted code would not be idiomatic. For example, how would 
you convert this code:

```dart?
onPressed: () { a*= a },
```

What is `a`? Is it atom? Is it class field? is it a parameter that 
will be modified somewhere else? 

So I see little value in converting expressions.

But rewriting widgets tree is a routine part in most of the 
caseses.

This code:

```dart 
Center(
  child: Text(
    _active ? 'Active' : 'Inactive',
    style: const TextStyle(fontSize: 32.0, color: Colors.white),
  ),
);
```

literally translates into:

```clojure
(m/Center
 :child (m/Text
     (if _active "Active" "Inactive")
     :style (m/TextStyle :fontSize 32.0 :color m.Colors/white)))
```

## Supported

- constructors invocation;
- static, factory methods invocation;
- named arguments;
- (typed) lists, maps;
- math and logical expressions;
- ternary operators;
- lambdas;
- comments (will be removed);

## Not supported

- constants;
- class declarations;
- methods declarations;
- bitwise 
- compound Assignment

## How to use

There are two options now: jar file or jvm repl.

## Contribution

### Installation

Download from https://github.com/dumch/dartclojure

### Usage

Build jar:
  
    $ clj -X:depstar

Run tests:

    $ clj -X:test

## License

Copyright © 2022 Artur Dumchev

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

[1]: https://github.com/Tensegritics/ClojureDart/blob/main/doc/flutter-helpers.md
