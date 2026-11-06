# Inventário PostgreSQL somente leitura (T-05.2)

O comando `--inventory-readonly` produz um relatório JSON para apoiar a classificação da T-05.1. Ele não migra, cria objetos ou altera permissões. Uma classificação `SUPPORTED_V1_V4` ou `SUPPORTED_V1_V5` significa apenas candidato técnico; exige aprovação operacional, evidência de backup restaurável, revisão dos consumidores e os demais checkpoints antes de qualquer transição.

## Execução

Execute o JAR em processo separado, com uma identidade de leitura já provisionada e credenciais fornecidas por um canal protegido. Não use a identidade de provisionamento nem `app_runtime` por conveniência. Não passe senha em argumento de processo, shell compartilhado ou log.

Variáveis necessárias:

- `INVENTORY_DB_URL`
- `INVENTORY_DB_USERNAME`
- `INVENTORY_DB_PASSWORD`

Variável opcional, sem segredo, para referência operacional do backup:

- `INVENTORY_BACKUP_EVIDENCE_REF`

Exemplo de invocação após configurar as variáveis por um mecanismo seguro:

```text
java -jar java-modernization-platform-0.0.1-SNAPSHOT.jar --inventory-readonly
```

Saída `0` indica somente que o estado técnico foi classificado como V1–V4 ou V1–V5 candidato; saída `3` indica recusa ou estado desconhecido; saída `1` indica falha operacional. O relatório não inclui valores de senha, hashes ou linhas com dados pessoais. O operador deve proteger o arquivo/canal em que guardar o JSON, pois ele contém nomes de roles, objetos, owners, ACLs, contagens e identidade do banco.

## Garantias e limites

- A conexão principal usa `REPEATABLE READ`, `READ ONLY`, `Connection.setReadOnly(true)` e faz rollback ao terminar.
- A consulta Flyway é somente `info()` e recebe `readOnly=true` na URL JDBC; não chama `migrate`, `validate` com reparo, `repair`, `baseline` ou `clean`.
- A conta de inventário deve ter leitura suficiente para catálogos, histórico e contagens. Não conceda privilégios adicionais durante a execução. Consulta sem permissão é `NOT_VERIFIABLE`/recusa, nunca aprovação silenciosa.
- A inspeção de consumidores, sessões antigas, backup e restauração depende de evidência operacional externa. `INVENTORY_BACKUP_EVIDENCE_REF` apenas registra uma referência fornecida pelo operador; não prova que o backup existe ou foi restaurado com sucesso.
- A fotografia de contagens é consistente dentro de uma transação `REPEATABLE READ`. Não copia linhas pessoais nem calcula checksum integral dos dados.
- A faixa suportada é somente PostgreSQL 16, schema público e objetos esperados pelas migrations V1–V5. Objetos, schemas, roles, histórico ou dependências extras resultam em recusa ou estado desconhecido para diagnóstico manual.
- O inventário não executa a transição, não confirma backup, não para a API e não autoriza corte de tráfego.
