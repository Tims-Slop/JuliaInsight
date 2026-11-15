# Third-party notices

## Julia (MIT License)

Julia Insight contains material derived from the Julia language (https://github.com/JuliaLang/julia):

- `src/main/resources/unicode/latex_symbols.tsv` is generated (by `scripts/gen_latex_table.jl`) from Julia's REPL
  tab-completion tables `stdlib/REPL/src/latex_symbols.jl` and `stdlib/REPL/src/emoji_symbols.jl`.
  Julia in turn derived those tables from the W3C `unicode.xml` character mapping, the LaTeX `unicode-math`
  symbol list and the `iamcal/emoji-data` project (MIT License).
- `src/main/kotlin/dev/juliainsight/lexer/JuliaChars.kt` re-implements the identifier and operator character rules
  of Julia's `src/flisp/julia_extensions.c`.

Julia is distributed under the following license:

```
MIT License

Copyright (c) 2009-2025: Jeff Bezanson, Stefan Karpinski, Viral B. Shah, and other contributors: https://github.com/JuliaLang/julia/contributors

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## Gradle wrapper (Apache License 2.0)

`gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` are part of Gradle (https://gradle.org),
licensed under the Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0).

## Trademarks

"Julia" and the Julia logo are trademarks of their respective owners; "PyCharm", "IntelliJ" and "JetBrains" are
trademarks of JetBrains s.r.o. Julia Insight is an independent project and is not affiliated with or endorsed by
the Julia project or JetBrains. It does not use the Julia logo.
