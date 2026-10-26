# Plano técnico — Iniciativa 006

**Status:** aguardando aprovação do plano revisado  
**Fase:** Plan  
**Especificação:** [spec.md](spec.md)

## 1. Decisões e direção técnica

D-01 a D-05 e DP-01 a DP-06 estão aprovadas e incorporadas à [especificação](spec.md). A análise documental da T-01 foi concluída; isso não valida implementação nem concede autorização para tarefas de implementação.

Direções obrigatórias: comando one-shot interativo sem eco para bootstrap; outro ADMIN existente implica recusa sem mutação; role bootstrap exclusiva; Flyway em job/processo one-shot separado da API; transição de bancos existentes versionada e forward-only sob responsabilidade da T-05; runtime sem credenciais privilegiadas; container com UID/GID numéricos estáveis não root. Não criar endpoint HTTP de bootstrap. A política JWT quando ADMIN persistido for rebaixado permanece fora do escopo.

## 2. Caracterização atual e impactos

- `DataInitializer.java` executa seed fixo de ADMIN no startup; deve deixar de executar qualquer criação automática.
- `AuthService` força `Role.USER` no registro público e usa `PasswordEncoder`; bootstrap deve ser fluxo operacional separado e reutilizar o encoder/validações sem chamar registro público.
- `User` tem email único; `UserRepository` oferece consulta case-insensitive. O contrato do comando usa e-mail normalizado, nunca atualiza usuário existente.
- `application.yml` executa Flyway no startup e usa datasource comum, com defaults `postgres`. A API deve passar a receber apenas runtime credentials; job de migration recebe as suas credenciais separadamente.
- Compose usa a conta `postgres` tanto para inicialização quanto para API. Deve passar a ter papéis e ordem de inicialização separados.
- Migration real está em `src/main/resources/db/migration/`; V1 executa `CREATE EXTENSION IF NOT EXISTS pgcrypto`. Não editar migrations aplicadas.
- Testcontainers atual usa `@ServiceConnection` com usuário inicial do container. Esse contexto pode continuar útil para setup, mas não prova grants runtime; criar conexões distintas para setup, migration e API.
- Dockerfile não declara usuário. A imagem final deve rodar com UID/GID numéricos não privilegiados, preservando leitura do JAR/certificados, temporários, JVM, acesso ao Postgres e health checks.

## 3. Abordagens de implementação propostas

### 3.1 Bootstrap (T-02)

Criar uma entrada one-shot acionada por comando administrativo explícito, executada sem servidor HTTP e encerrada após o resultado. Não basta ativar um profile que também inicie a API ou Flyway com credencial privilegiada.

Ler a senha de stdin em terminal interativo sem eco; nunca de argumento de processo. Bootstrap automatizado não está aprovado. Se vier a ser necessário, decidir separadamente o canal não interativo antes de implementá-lo; não introduzir secret manager nesta iniciativa. Normalizar e validar e-mail conforme `AuthService` (lowercase com `Locale.ROOT`, sem `strip`; duplicidade consultada sem diferenciar maiúsculas/minúsculas), validar nome/senha segundo regras existentes de cadastro e usar o `PasswordEncoder` BCrypt existente. A inicialização normal da API não exige configurações de bootstrap. O resultado operacional deve distinguir criado, no-op, conflito e falha com códigos de saída documentados em CA-04a, sem imprimir senha.

Dentro de uma transação, coordenar concorrência com lock PostgreSQL transacional (por exemplo, advisory lock) ou mecanismo equivalente comprovado com sessões independentes. Em seguida: mesmo e-mail já ADMIN => no-op, sem comparar/redefinir senha; mesmo e-mail já USER => conflito; e-mail novo e qualquer outro ADMIN existente => conflito/recusa; sem ADMIN e e-mail livre => inserir o primeiro ADMIN. Não atualizar ou promover registros existentes. Rollback em falha.

A role exclusiva de bootstrap nunca é injetada na API. O fluxo público de cadastro persiste usuários `USER`, portanto a role runtime precisa ter escrita necessária em `users`. A política deve ser imposta pelo PostgreSQL: permitir esse cadastro e impedir INSERT de ADMIN, promoção de USER para ADMIN e alteração/desativação da proteção pela role runtime. O mecanismo (trigger, RLS, função ou equivalente) permanece decisão técnica para prova T-03; não conceder nem negar INSERT indiscriminadamente. Preservar as operações Customer-link e INSERT de auditoria necessárias, sem UPDATE/DELETE de auditoria.

### 3.2 Flyway, roles e grants (T-03)

Executar Flyway em processo/job one-shot antes do rollout da API. Avaliar duas opções de engenharia: job usando a dependência Flyway já presente no projeto, ou CLI/plugin com versão e localização de migrations sincronizadas. Preferir a que reutilize os arquivos versionados com menor duplicação e não inicialize a API com segredo de migration. A seleção decorre de análise técnica em T-01, sem alterar o contrato aprovado.

Provisionar separadamente: identidade administrativa do banco; migrator não superusuário com direitos de DDL/ownership estritamente necessários; runtime com `USAGE` no schema e DML derivado das queries reais; bootstrap role exclusiva e ausente da API. Runtime pode inserir registros `USER` pelo cadastro e executar Customer-link e auditoria, mas a proteção PostgreSQL deve negar criação/promoção para ADMIN e não pode ser removível ou desativável pelo runtime. Runtime não recebe DDL, `CREATE ROLE/DB`, `DELETE` ou `TRUNCATE`; auditoria exige INSERT sem UPDATE/DELETE. Validar SQL Hibernate para escolher grants por tabela/coluna.

Investigar permissões reais de `pgcrypto` em PostgreSQL 16. Se instalar extensão exigir direito que o migrator não deve ter, pré-provisionar extensão por identidade administrativa, sem dar superuser ao migrator. Banco novo: provisionar papéis e pré-requisitos, migrar, verificar grants, iniciar API. Falha do job bloqueia início/rollout da nova versão; não apagar banco nem volume.

### 3.3 Transição de bancos existentes (T-05)

Antes da mudança, inventariar versão do schema/Flyway, owner de schema/objetos, extensões, usuários/roles, grants, dados e serviços que usam credenciais atuais. Planejar janela/ordem de rollout e validar backup restaurável. Executar procedimento versionado, idempotente e forward-only para criar roles, transferir ownership quando necessário e aplicar grants. Conferir estado de migration, consultas de integridade e fluxos da API com runtime role. Não reescrever migrations antigas nem presumir rollback automático.

Se falhar: interromper rollout; preservar banco e versão anterior quando compatível; corrigir a etapa de roles/grants e reexecutar de forma idempotente. Restaurar backup é recuperação operacional sujeita a avaliação de perda de transações, não rollback automático. Nunca retornar silenciosamente a API para superuser. Registrar pré-condições e limites no runbook.

### 3.4 Ambientes, Testcontainers e segredo

- **Desenvolvimento:** comando interativo explícito; roles e migrations em passo próprio; API só runtime; sem fallback de senha `postgres`.
- **Testes:** Testcontainers PostgreSQL 16; identidade privilegiada apenas para fixture/provisionamento; Flyway com migrator; contexto da aplicação com runtime. Testes específicos usam sessões independentes para provar isolamento. Credenciais são efêmeras e não reutilizadas fora do teste.
- **Produção:** provisionamento por operador autorizado; bootstrap interativo controlado; migration job antes da API; somente runtime secret no processo API. Nenhuma etapa automática cria ADMIN.

Entrada não interativa do bootstrap não está aprovada. Se uma operação futura precisar automatizar o comando, registrar decisão operacional específica para o canal de segredo antes de implementá-la. Não adicionar secret manager nesta iniciativa sem nova autorização.

### 3.5 Imagem nonroot (T-04)

Definir UID/GID numéricos estáveis e não privilegiados. Dar leitura ao JAR, truststore/certificados e configuração; manter diretório da aplicação sem escrita; conceder escrita apenas ao diretório temporário realmente usado pela JVM/aplicação. As portas atuais 8080/8081 não exigem root. Evitar permissões amplas e considerar incompatibilidade com bind mounts.

Executar a imagem final e registrar UID/GID efetivos; validar inicialização JVM, conexão PostgreSQL, health checks existentes, leitura dos artefatos e escrita em temporário. Provar que diretórios não necessários não são graváveis. `USER` no Dockerfile sem verificação de execução não conclui a tarefa.

## 4. Sequência de tarefas e dependências

Preservar a numeração T-01 a T-06. Ordem recomendada de execução: **T-01 → T-03 → T-02 → T-04 → T-05 → T-06**.

| Ordem | Tarefa | Dependências técnicas | Resultado esperado |
|---|---|---|---|
| 1 | T-01 — Caracterizar estado e fechar contrato técnico | Concluída no plano documental; sem implementação | Registrar comportamento atual, contrato do comando, fluxo de credenciais, executor Flyway possível, grants e critérios de transição; sem implementação. |
| 2 | T-03 — Separar roles e validar migrations/privilégios | T-01 | Provar roles de provisionamento/migration/runtime/bootstrap, `pgcrypto`, migrations V1–V4 em PostgreSQL descartável e operações permitidas/negadas; adaptar Testcontainers sem enfraquecer testes. Não validar transição de banco existente. |
| 3 | T-02 — Bootstrap explícito idempotente | T-01 e **T-03 antes da integração** para role/credenciais; desenvolvimento isolado de domínio pode ocorrer após aprovação, sem conceder integração antecipada | Implementar comando, transação/concorrência, stdin sem eco e testes. A API nunca recebe bootstrap credential. |
| 4 | T-04 — Imagem nonroot | T-01 para UID/GID e requisitos de filesystem | Executar imagem real e validar permissões, JVM, Postgres, temporários e health. |
| 5 | T-05 — Transição segura de bancos existentes e documentação operacional | T-02, T-03, T-04 | Validar estados iniciais suportados em fixture/clone, preservar dados e histórico Flyway, aplicar transição forward-only e documentar pré-condições, procedimento, verificações e recuperação; completar a documentação operacional sem expor segredos. |
| 6 | T-06 — Regressão e verificação integrada | T-02 a T-05 | Executar matriz CA, regressões, PostgreSQL/Testcontainers, bootstrap e imagem; registrar evidência real. |

T-03 precede a integração do comando porque a credencial exclusiva de bootstrap e a separação runtime/migration precisam existir antes de injetar qualquer segredo no processo one-shot. T-05 depende dos mecanismos de privilégio de T-03, da integração de T-02 e da configuração de container de T-04. A transição de bancos existentes pertence exclusivamente a T-05. Essa ordem não autoriza nenhuma tarefa.

## 5. Arquivos provavelmente afetados (não autorização)

- `DataInitializer.java`; implementação do comando/serviço operacional em módulo apropriado.
- `application.yml`, `application-test.yml`; Compose, `.env.example`, Dockerfile e README.
- Procedimento de provisionamento versionado; migration nova somente se estritamente necessária e aprovada, nunca editar migration aplicada.
- Testes de bootstrap, PostgreSQL/Testcontainers e smoke check da imagem; CI somente se necessário.

Não consultar `.env` local. Não criar scripts ou executar alterações nesta etapa documental.

## 6. Evidência de conclusão

T-06 deve mapear CA-01–CA-14 a verificações concretas. Registrar papéis e grants reais, credenciais disponíveis a cada processo, resultado das tentativas proibidas, coordenação da concorrência, evidências da transição de bancos existentes concluída em T-05 e UID/GID observado na imagem. A aprovação das decisões não equivale a validação técnica.
