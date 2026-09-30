import java.nio.file.*;
import java.sql.*;

/**
 * 迁移脚本干跑工具：在事务里执行整份 SQL 后回滚，用来在应用重启前发现语法/引用错误。
 * 用法：java -cp <postgresql-驱动 jar> tools/PgExec.java check V18__xxx.sql
 *      java -cp <jar> tools/PgExec.java apply V18__xxx.sql   （真的写入，供本地补种数据用）
 */
public class PgExec {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "check";
        String file = args.length > 1 ? args[1] : "";
        String url = System.getenv().getOrDefault("FLOWER_DB_URL", "jdbc:postgresql://localhost:5432/flowerv1");
        String user = System.getenv().getOrDefault("FLOWER_DB_USERNAME", "postgres");
        String pass = System.getenv().getOrDefault("FLOWER_DB_PASSWORD", "1234");
        String body = new String(Files.readAllBytes(Paths.get(file)), java.nio.charset.StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(body);
                System.out.println("OK " + file + " 语句执行通过");
            } catch (SQLException e) {
                System.out.println("FAIL " + file + " -> " + e.getSQLState() + " " + e.getMessage());
                c.rollback();
                System.exit(1);
            }
            if ("apply".equals(mode)) {
                c.commit();
                System.out.println("APPLIED " + file);
            } else {
                c.rollback();
                System.out.println("已回滚，数据库未被改动");
            }
        }
    }
}
