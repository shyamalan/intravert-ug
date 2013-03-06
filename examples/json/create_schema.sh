echo "Creating keyspace 'myks' and column family 'mycf'"
curl -vX POST http://localhost:8080/myapp/intrareq-json -d "{\"e\":[ {\"type\":\"CREATEKEYSPACE\",\"op\":{\"name\":\"myks\",\"replication\":1}}, {\"type\":\"CREATECOLUMNFAMILY\",\"op\":{\"name\":\"mycf\"}} ]}"
echo