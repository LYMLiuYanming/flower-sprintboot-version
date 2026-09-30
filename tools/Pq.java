import java.nio.file.*;
import java.sql.*;

/** 只读查询小工具：java -cp <pg jar> tools/Pq.java "select ..." */
public class Pq {
    public static void main(String[] args) throws Exception {
        String sql = args.length > 0 ? String.join(" ", args) : "select 1";
        if (sql.toLowerCase().matches(".*(insert|update|delete|alter|drop|create|truncate).*")) {
            System.out.println("REFUSED: 本工具只允许只读查询");
            System.exit(2);
        }
        String url = System.getenv().getOrDefault("FLOWER_DB_URL", "jdbc:postgresql://localhost:5432/flowerv1");
        try (Connection c = DriverManager.getConnection(url,
                System.getenv().getOrDefault("FLOWER_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("FLOWER_DB_PASSWORD", "1234"));
             Statement st = c.createStatement()) {
            st.setMaxRows(60);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                StringBuilder head = new StringBuilder();
                for (int i = 1; i <= n; i++) head.append(md.getColumnLabel(i)).append(i == n ? "\n" : " | ");
                System.out.println(head);
                while (rs.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= n; i++) row.append(rs.getString(i)).append(i == n ? "" : " | ");
                    System.out.println(row);
                }
            }
        }
    }
}
