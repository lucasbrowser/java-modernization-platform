# Inventário PostgreSQL somente leitura (T-05.2)

O comando `--inventory-readonly` produz um relatório JSON para apoiar a classificação da T-05.1. Ele não migra, cria objetos ou altera permissões. Uma classificação `SUPPORTED_V1_V4` ou `SUPPORTED_V1_V5` significa apenas candidato técnico; exige aprovação operacional, evidência de backup restaurável, revisão dos consumidores e os demais checkpoints antes de qualquer transição.

## Execução

Execute o JAR em processo separado, com uma identidade de leitura já provisionada e credenciais fornecidas por um canal protegido. Não use a identidade de provisionamento nem `app_runtime` por conveniência. Não passe senha em argumento de processo, shell compartilhado ou log.

Variáveis necessárias:

- `INVENTORY_DB_URL`
- `INVENTORY_DB_USERNAME`
- `INVENTORY_DB_PASSWORD`

Variável opcional, sem segredo, para indicar explicitamente o nome da role provisionadora que o inventário deve inspecionar:

- `INVENTORY_PROVISIONER_ROLE`

O nome não é inferido de `INVENTORY_DB_USERNAME`, `session_user` ou `current_user`. Se a variável estiver ausente/vazia, o relatório registra a identidade como `NOT_VERIFIABLE` e a classificação não pode ser positiva. Se apontar para uma role inexistente, a role é registrada como inexistente (`roleExists: PRESENT, value: false`) e seus atributos são `NOT_APPLICABLE`; isso difere de atributo SQL NULL.

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

## Atributos de roles e estados de coleta (D1)

O relatório `roles` mantém `app_migrator`, `app_runtime` e `app_bootstrap`; `provisioner` é um campo separado, preenchido somente a partir do nome explícito `INVENTORY_PROVISIONER_ROLE`. Para cada role identificada são consultados `rolsuper`, `rolcreaterole`, `rolcreatedb`, `rolreplication`, `rolbypassrls`, `rolinherit`, `rolcanlogin`, `rolvaliduntil` e `rolconfig`. Isso coleta atributos; não avalia ainda grafo de memberships transitivas nem as capacidades completas da provisionadora.

Cada atributo usa objeto `{ "state": ..., "value": ..., "reason": ... }`:

- `PRESENT`: valor coletado, inclusive `false` ou lista vazia.
- `NULL_VALUE`: coluna PostgreSQL coletada retornou NULL; hoje aplicável a `rolvaliduntil` (sem expiração de autenticação por senha) e `rolconfig` (sem configuração específica).
- `NOT_APPLICABLE`: role conhecida como inexistente; seus atributos não existem. A própria existência é registrada separadamente.
- `NOT_COLLECTED`: estado suportado pelo modelo e serializador quando o coletor não executa a consulta; o coletor D1 tenta todos os nove atributos em PostgreSQL 16, portanto não emite esse estado em execução normal.
- `NOT_VERIFIABLE`: consulta falhou, identidade provisionadora não foi configurada ou o valor não pode ser avaliado com segurança. Não é evidência de valor seguro.

O inventário não consulta `rolpassword`, hashes/verifiers, `.env` ou credenciais. A configuração `rolconfig` é tratada de modo conservador: NULL é reportado como `NULL_VALUE`; configuração vazia explicitamente coletada pode ser `PRESENT` com lista vazia; qualquer configuração não vazia é omitida integralmente e reportada `NOT_VERIFIABLE`, pois esta versão não valida uma allowlist de valores. Nenhuma parte do valor bruto é incluída em JSON ou mensagem de erro. Consequentemente, configuração não vazia impede classificação positiva até uma política de avaliação segura ser aprovada e implementada.

Os atributos booleanos conhecidos das roles existentes são comparados com o perfil parcial atual do inventário; `rolbypassrls` true na provisionadora e flags privilegiadas `rolsuper`, `rolcreatedb` ou `rolreplication` true bloqueiam o candidato. `rolcreaterole`, validade, herança e login são reportados, mas a política completa por estágio depende das decisões posteriores de provisionamento/membership. `rolvaliduntil = NULL` significa sem expiração para autenticação por senha; não é convertido em false nem tratado como expiração. Não há aprovação automática de D-07.

Se qualquer dado obrigatório estiver `NOT_COLLECTED` ou `NOT_VERIFIABLE`, a classificação não pode ser `SUPPORTED_*`. Role provisionadora sem configuração resulta em `UNKNOWN`; role configurada mas inexistente é recusa conhecida. O status do inventário continua sendo somente candidato técnico e não autoriza transição.
