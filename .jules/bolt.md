## 2024-05-18 - Lambda syntax compilation issue with Java 21+
**Learning:** Java 9+ treats single underscores (`_`) as reserved keywords, meaning using them in lambdas like `(_, _) -> true` will cause a compilation error. Using Java 21+ requires you to explicitly name lambda arguments even if they are unused, like `(c, i) -> true`. Note: some syntax may use it in Java 21+ as an unnamed pattern variable but the codebase might fail under older/newer contexts.
**Action:** Always name arguments explicitly in lambdas, do not use `_` as a variable name.
