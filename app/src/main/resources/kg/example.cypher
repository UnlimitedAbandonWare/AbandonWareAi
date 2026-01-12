MATCH (p:Person)-[:WORKS_AT]->(c:Company) RETURN p.name, c.name LIMIT 3;
