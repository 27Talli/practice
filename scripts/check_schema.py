#!/usr/bin/env python3
"""Supplemental SQLite validation reflected from the actual Room Kotlin sources.

This does not replace Room's annotation processor, an Android build, or instrumented
Room tests. Run from any working directory with Python 3 and its stdlib sqlite3.
No database file or production data is read or written.
"""

from pathlib import Path
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
ENTITY_SOURCE = ROOT / "app/src/main/java/com/offerpoint/pos/data/local/entity/Entities.kt"
DAO_SOURCE = ROOT / "app/src/main/java/com/offerpoint/pos/data/local/dao/PosDao.kt"


def quoted_names(text):
    return re.findall(r'"(.*?)"', text)


def read_entities():
    source = ENTITY_SOURCE.read_text()
    entities = {}
    pattern = r"@Entity(?P<ann>.*?)\ndata class (?P<name>\w+)\((?P<body>.*?)\)(?:\n|$)"
    for match in re.finditer(pattern, source, re.S):
        annotation, name, body = match["ann"], match["name"], match["body"]
        table = re.search(r'tableName\s*=\s*"(.*?)"', annotation)[1]
        fields = re.findall(r"val (\w+): (\w+)(\?)?", body)
        assert fields and len(fields) == len(re.findall(r"\bval\s", body)), name
        primary_match = re.search(r"primaryKeys\s*=\s*\[([^]]+)\]", annotation)
        if primary_match:
            primary = quoted_names(primary_match[1])
        else:
            primary = [re.search(r"@PrimaryKey(?:\([^)]*\))?\s+val (\w+)", body)[1]]
        foreign_keys = [
            (fk[1], quoted_names(fk[2]), quoted_names(fk[3]))
            for fk in re.finditer(
                r"ForeignKey\(entity = (\w+)::class, parentColumns = \[([^]]+)\], childColumns = \[([^]]+)\]",
                annotation,
            )
        ]
        assert len(foreign_keys) == annotation.count("ForeignKey("), name
        indices = [
            (quoted_names(index[1]), "unique = true" in index[1])
            for index in re.finditer(r"Index\(([^)]*)\)", annotation)
        ]
        entities[name] = (table, fields, primary, foreign_keys, indices)
    assert len(entities) == source.count("@Entity"), "Unparsed @Entity annotation"
    assert len(entities) == 14, f"Expected 14 entities, found {len(entities)}"
    return entities


def build_schema(connection, entities):
    for name, (table, fields, primary, foreign_keys, indices) in entities.items():
        definitions = []
        for column, kotlin_type, nullable in fields:
            assert kotlin_type in ("String", "Long", "Int", "Boolean"), (name, kotlin_type)
            sql_type = "TEXT" if kotlin_type == "String" else "INTEGER"
            definitions.append(f'"{column}" {sql_type}' + (" NOT NULL" if not nullable else ""))
        definitions.append("PRIMARY KEY (" + ",".join(f'"{column}"' for column in primary) + ")")
        for parent, parent_columns, child_columns in foreign_keys:
            parent_table, _, parent_primary, _, parent_indices = entities[parent]
            assert parent_columns == parent_primary or (parent_columns, True) in parent_indices, (
                name, "Foreign key lacks an exact parent unique key", parent, parent_columns
            )
            child_keys = [primary] + [columns for columns, _ in indices]
            assert any(columns[: len(child_columns)] == child_columns for columns in child_keys), (
                name, "Foreign key lacks a covering child index", child_columns
            )
            definitions.append(
                "FOREIGN KEY (" + ",".join(f'"{column}"' for column in child_columns)
                + f") REFERENCES {parent_table} ("
                + ",".join(f'"{column}"' for column in parent_columns) + ") ON DELETE RESTRICT"
            )
        connection.execute(f"CREATE TABLE {table} (" + ",".join(definitions) + ")")
        for number, (columns, unique) in enumerate(indices):
            connection.execute(
                f"CREATE {'UNIQUE ' if unique else ''}INDEX check_{table}_{number} ON {table} ("
                + ",".join(f'"{column}"' for column in columns) + ")"
            )


def compile_dao_queries(connection):
    source = DAO_SOURCE.read_text()
    statements = []
    for match in re.finditer(r'@Query\((""".*?"""|"[^"\n]*")\)', source, re.S):
        raw = match[1]
        sql = raw[3:-3] if raw.startswith('"""') else raw[1:-1]
        parameters = {name: 0 for name in re.findall(r":(\w+)", sql)}
        connection.execute("EXPLAIN " + sql, parameters).fetchall()
        statements.append(sql)
    assert len(statements) == source.count("@Query("), "Unparsed @Query annotation"
    return statements


def check_fixtures(connection, entities, statements):
    def insert(table, **values):
        metadata = next(entity for entity in entities.values() if entity[0] == table)
        row = {
            name: None if nullable else "" if kotlin_type == "String" else 0
            for name, kotlin_type, nullable in metadata[1]
        }
        row.update(values)
        connection.execute(
            f"INSERT INTO {table} (" + ",".join(f'"{column}"' for column in row)
            + ") VALUES (" + ",".join("?" for _ in row) + ")", list(row.values())
        )

    def rejects(action):
        try:
            action()
        except sqlite3.IntegrityError:
            return
        raise AssertionError("Expected a uniqueness or foreign-key constraint rejection")

    insert("shops", id=1, code="OP")
    insert("shops", id=2, code="FF")
    insert("products", id=1, sku="OP-000001", name="Literal %_ lipstick", isActive=1)
    insert("products", id=2, sku="FF-000001", name="Sandal", isActive=1)
    for shop in (1, 2):
        for product in (1, 2):
            insert("shop_products", shopId=shop, productId=product, isActive=1)
            insert("inventory", shopId=shop, productId=product, quantity=10)

    insert("sales", id=1, shopId=1, receiptNumber="1")
    insert("sales", id=2, shopId=2, receiptNumber="1")
    insert("sales", id=3, shopId=1, receiptNumber="2")
    insert("sale_items", id=1, saleId=1, shopId=1, productId=1)
    insert("sale_items", id=3, saleId=3, shopId=1, productId=1)
    rejects(lambda: insert("sale_items", id=2, saleId=1, shopId=2, productId=1))
    insert("returns", id=1, saleId=1, shopId=1)
    rejects(lambda: insert("returns", id=2, saleId=1, shopId=2))
    insert("return_items", id=1, returnId=1, saleId=1, shopId=1, saleItemId=1, productId=1)
    rejects(lambda: insert("return_items", id=2, returnId=1, saleId=1, shopId=1, saleItemId=1, productId=2))
    rejects(lambda: insert("return_items", id=3, returnId=1, saleId=2, shopId=2, saleItemId=1, productId=1))
    rejects(lambda: insert("return_items", id=4, returnId=1, saleId=1, shopId=1, saleItemId=3, productId=1))
    insert("stock_movements", id=1, shopId=1, productId=1, saleItemId=1)
    rejects(lambda: insert("stock_movements", id=2, shopId=2, productId=1, saleItemId=1))
    rejects(lambda: insert("stock_movements", id=3, shopId=1, productId=2, returnItemId=1))
    insert("product_barcodes", id=1, productId=1, value="OP-000001", kind="INTERNAL_CODE128")
    insert("product_barcodes", id=3, productId=1, value="000123", kind="MANUFACTURER")
    rejects(lambda: insert("product_barcodes", id=2, productId=2, value="OP-000001", kind="MANUFACTURER"))
    rejects(lambda: insert("product_barcodes", id=4, productId=2, value="000123", kind="MANUFACTURER"))

    stock_update = next(sql for sql in statements if sql.startswith("UPDATE inventory"))
    assert connection.execute(stock_update, dict(quantity=-1, updatedAt=1, shopId=1, productId=1)).rowcount == 0
    assert connection.execute("SELECT quantity FROM inventory WHERE shopId=1 AND productId=1").fetchone()[0] == 10
    assert connection.execute(stock_update, dict(quantity=12, updatedAt=1, shopId=1, productId=1)).rowcount == 1
    assert connection.execute("SELECT quantity FROM inventory WHERE shopId=2 AND productId=1").fetchone()[0] == 10

    search = next(sql for sql in statements if "instr(lower(p.name)" in sql)
    results = connection.execute(search, dict(shopId=1, query="%_")).fetchall()
    assert len(results) == 1 and results[0][0] == 1, "Search metacharacters must be literal"
    results = connection.execute(search, dict(shopId=2, query="000123")).fetchall()
    assert len(results) == 1 and results[0][1] == 2 and results[0][6] == "000123"
    assert not connection.execute("PRAGMA foreign_key_check").fetchall()


def main():
    entities = read_entities()
    with sqlite3.connect(":memory:") as connection:
        connection.execute("PRAGMA foreign_keys=ON")
        build_schema(connection, entities)
        statements = compile_dao_queries(connection)
        print(f"PASS: {len(entities)} reflected entity definitions; exact FK parent uniqueness and covering child indices.")
        print(f"PASS: all {len(statements)} DAO SQL statements compile with SQLite {sqlite3.sqlite_version}.")
        check_fixtures(connection, entities, statements)
        print("PASS: cross-shop/cross-sale/wrong-product lineage and global barcode collision checks.")
        print("PASS: nonnegative stock update, shop stock isolation, literal search, leading-zero barcode, foreign_key_check.")
        print("Supplemental SQLite checks only; Android/Room build and instrumented tests remain required.")


if __name__ == "__main__":
    main()
