// 给 src/bootstrap.sql.js 的类型声明。
//
// 那个文件是纯 JS（里面只有一段 SQL 字符串），用 allowJs 把它整个拉进
// 编译单元没必要，写一行声明就够了。
// TypeScript 解析 `./bootstrap.sql.js` 时会自动找 `bootstrap.sql.d.ts`。
export declare const BOOTSTRAP_SQL: string;
