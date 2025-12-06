## Pattern: ImportBeforePackage
**Symptom**: `class, interface, enum, or record expected` at `package ...` line.  
**Cause**: One or more `import ...;` lines appear *before* the `package` declaration.  
**Fix**: Move `package` to be the first non-comment line and place all `import` lines after it. (Auto-fixed by reordering script.)