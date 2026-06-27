# AGENTS.md instructions

Coding preferences:
- Do not default to test-driven development.
- Do not create test skeletons or new test classes unless I explicitly ask for tests, the task is a bugfix that needs a focused regression test, or the existing project already has directly relevant tests to extend.
- Prefer implementing the requested behavior first, then run the smallest useful verification command or describe the manual check.

Commenting preferences:
- When generating or changing Java code, add concise Chinese comments for business logic, control-flow decisions, and non-obvious framework behavior.
- For controllers, services, interceptors, configuration classes, and Redis/database related code, prefer a short method-level comment plus inline comments for key steps.
- Explain why the code does something or what business rule it represents; do not merely repeat the code in words.
- Do not comment trivial assignments, getters, setters, constructors, imports, or obvious variable declarations.
- Keep comments short and practical, suitable for a learning project.
