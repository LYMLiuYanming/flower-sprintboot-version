import org.flywaydb.core.Flyway;

/**
 * 只用于修正 flyway_schema_history 里已应用迁移的 checksum（repair），不执行任何 DDL。
 * 用法：java -cp <flyway-core>:<flyway-pg>:<pg-driver> tools/FlywayRepair.java
 */
public class FlywayRepair {
    public static void main(String[] args) {
        String url = System.getenv().getOrDefault("FLOWER_DB_URL", "jdbc:postgresql://localhost:5432/flowerv1");
        String user = System.getenv().getOrDefault("FLOWER_DB_USERNAME", "postgres");
        String pass = System.getenv().getOrDefault("FLOWER_DB_PASSWORD", "1234");
        Flyway flyway = Flyway.configure()
                .dataSource(url, user, pass)
                .locations("filesystem:src/main/resources/db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .outOfOrder(true)
                .load();
        flyway.repair();
        // Flyway 大版本改过 RepairResult 的字段名，这里只报成败不报明细
        System.out.println("REPAIR OK checksums realigned");
    }
}
