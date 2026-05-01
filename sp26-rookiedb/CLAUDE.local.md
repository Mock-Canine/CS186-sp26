# CLAUDE.local.md

## Markdown Explanation File Placement

When the user asks for an explanation of a module or package under
`src/main/java/edu/berkeley/cs186/database/`, generate the markdown file
inside the **same source directory** being explained.

Examples:
- Explaining `src/main/java/.../database/index/` → place the `.md` file in `src/main/java/.../database/index/`
- Explaining `src/main/java/.../database/memory/` → place the `.md` file in `src/main/java/.../database/memory/`
- Explaining top-level files in `src/main/java/.../database/` → place the `.md` file in `src/main/java/.../database/`

Do NOT place explanation markdown files in the project root or in test directories.
