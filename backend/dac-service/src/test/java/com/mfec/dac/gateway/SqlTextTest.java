package com.mfec.dac.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SqlTextTest {

  @Test
  @DisplayName("Splits on semicolons outside strings, identifiers, comments and dollar quotes")
  void splits() {
    assertThat(SqlText.split("SELECT 1; SELECT ';' ; SELECT \"a;b\" FROM t -- x;y\n; ; SELECT $q$;$q$"))
        .containsExactly("SELECT 1", "SELECT ';'", "SELECT \"a;b\" FROM t -- x;y", "SELECT $q$;$q$");
    assertThat(SqlText.split("  ;  ; ")).isEmpty();
    assertThat(SqlText.split("SELECT 1 /* ; /* nested ; */ ; */")).hasSize(1);
  }

  @Test
  @DisplayName("Substitutes parameters as literals and never inside a string")
  void substitutes() throws PgError {
    String sql = "SELECT * FROM t WHERE a = $1 AND b = '$2' AND c = $2";
    assertThat(SqlText.parameterCount(sql)).isEqualTo(2);
    String out =
        SqlText.substitute(sql, List.of(SqlText.literal("x'; DROP TABLE t; --", PgTypes.TEXT), SqlText.literal("42", PgTypes.INT4)));
    assertThat(out).isEqualTo("SELECT * FROM t WHERE a =  'x''; DROP TABLE t; --'  AND b = '$2' AND c =  42 ");
    assertThat(SqlText.split(out)).hasSize(1);
  }

  @Test
  @DisplayName("A number goes in bare only when the parameter is numeric and the value is a number")
  void literals() {
    assertThat(SqlText.literal(null, PgTypes.INT4)).isEqualTo("NULL");
    assertThat(SqlText.literal("-1", PgTypes.INT4)).isEqualTo("-1");
    assertThat(SqlText.literal("1 OR 1=1", PgTypes.INT4)).isEqualTo("'1 OR 1=1'");
    assertThat(SqlText.literal("42", PgTypes.TEXT)).isEqualTo("'42'");
    assertThat(SqlText.literal("a\\'b", PgTypes.TEXT)).isEqualTo("'a\\''b'");
  }

  @Test
  @DisplayName("A negative number after a minus sign cannot become a comment")
  void negativeAfterMinus() throws PgError {
    String out = SqlText.substitute("SELECT 5-$1", List.of(SqlText.literal("-1", PgTypes.INT4)));
    assertThat(out).doesNotContain("--");
  }

  @Test
  @DisplayName("An unknown parameter number is a protocol error")
  void unknownParameter() {
    assertThatThrownBy(() -> SqlText.substitute("SELECT $3", List.of("1")))
        .isInstanceOf(PgError.class)
        .extracting(e -> ((PgError) e).sqlState())
        .isEqualTo(PgError.PROTOCOL_VIOLATION);
  }

  @Test
  @DisplayName("Classifies statements by their leading words")
  void kinds() {
    assertThat(SqlText.kind("  /* hi */ select 1")).isEqualTo(SqlText.Kind.QUERY);
    assertThat(SqlText.kind("(SELECT 1)")).isEqualTo(SqlText.Kind.QUERY);
    assertThat(SqlText.kind("WITH x AS (SELECT 1) SELECT * FROM x")).isEqualTo(SqlText.Kind.QUERY);
    assertThat(SqlText.kind("SET search_path = public")).isEqualTo(SqlText.Kind.SET);
    assertThat(SqlText.kind("SHOW TIME ZONE")).isEqualTo(SqlText.Kind.SHOW);
    assertThat(SqlText.kind("START TRANSACTION READ ONLY")).isEqualTo(SqlText.Kind.BEGIN);
    assertThat(SqlText.kind("rollback to savepoint a")).isEqualTo(SqlText.Kind.SAVEPOINT);
    assertThat(SqlText.kind("ROLLBACK")).isEqualTo(SqlText.Kind.ROLLBACK);
    assertThat(SqlText.kind("DISCARD ALL")).isEqualTo(SqlText.Kind.IGNORED);
    assertThat(SqlText.kind("INSERT INTO t VALUES (1)")).isEqualTo(SqlText.Kind.OTHER);
    assertThat(SqlText.kind("-- only a comment")).isEqualTo(SqlText.Kind.EMPTY);
    assertThat(SqlText.tag("START TRANSACTION", SqlText.Kind.BEGIN)).isEqualTo("START TRANSACTION");
    assertThat(SqlText.tag("DISCARD ALL", SqlText.Kind.IGNORED)).isEqualTo("DISCARD ALL");
  }

  @Test
  @DisplayName("Routes catalog questions to the sandbox and data to the proxy, never both")
  void routes() {
    assertThat(SqlText.route("SELECT version()")).isEqualTo(SqlText.Route.CATALOG);
    assertThat(SqlText.route("SELECT * FROM pg_catalog.pg_namespace")).isEqualTo(SqlText.Route.CATALOG);
    assertThat(SqlText.route("SELECT oid FROM pg_type")).isEqualTo(SqlText.Route.CATALOG);
    assertThat(SqlText.route("SELECT * FROM information_schema.columns")).isEqualTo(SqlText.Route.CATALOG);
    assertThat(SqlText.route("SELECT * FROM sales.customer")).isEqualTo(SqlText.Route.DATA);
    assertThat(SqlText.route("SELECT * FROM customer c JOIN pg_class p ON true")).isEqualTo(SqlText.Route.MIXED);
    assertThat(SqlText.route("SELECT * FROM (SELECT * FROM customer) x WHERE x.id IN (SELECT oid FROM pg_type)"))
        .isEqualTo(SqlText.Route.MIXED);
    // A schema named like the catalog but not it is data.
    assertThat(SqlText.route("SELECT * FROM pg_stuff.orders")).isEqualTo(SqlText.Route.DATA);
  }

  @Test
  @DisplayName("Reads SET statements and leaves SET TRANSACTION and SESSION CHARACTERISTICS alone")
  void sets() {
    assertThat(SqlText.parseSet("SET search_path TO sales, public"))
        .isEqualTo(new SqlText.Setting("search_path", "sales, public"));
    assertThat(SqlText.parseSet("set session arak.purpose = 'fraud-review';"))
        .isEqualTo(new SqlText.Setting("arak.purpose", "'fraud-review'"));
    assertThat(SqlText.parseSet("SET TIME ZONE 'UTC'")).isEqualTo(new SqlText.Setting("timezone", "'UTC'"));
    assertThat(SqlText.parseSet("SET SESSION AUTHORIZATION someone").name()).isEqualTo("authorization");
    assertThat(SqlText.parseSet("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE")).isNull();
    assertThat(SqlText.parseSet("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY")).isNull();
    assertThat(SqlText.parameterOf("SHOW TRANSACTION ISOLATION LEVEL")).isEqualTo("transaction isolation level");
    assertThat(SqlText.parameterOf("show \"DateStyle\";")).isEqualTo("datestyle");
  }

  @Test
  @DisplayName("The first real schema of a search_path")
  void firstSchema() {
    assertThat(SqlText.firstSchema("\"$user\", public")).isEqualTo("public");
    assertThat(SqlText.firstSchema("pg_catalog, Sales")).isEqualTo("sales");
    assertThat(SqlText.firstSchema("\"Mixed\", public")).isEqualTo("Mixed");
    assertThat(SqlText.firstSchema("'$user'")).isNull();
  }
}
