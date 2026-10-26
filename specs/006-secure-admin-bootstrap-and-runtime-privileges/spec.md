# Iniciativa 006 — Secure Admin Bootstrap and Runtime Privileges

**Status:** aguardando aprovação dos documentos revisados  
**Fase:** Specify  
**Data:** 2026-10-23

## 1. Objetivo e limite desta revisão

Reduzir os privilégios e segredos expostos durante o bootstrap e a execução da plataforma. Esta versão registra decisões arquiteturais aprovadas e critérios verificáveis. A aprovação das decisões não comprova implementação nem autoriza a execução de tarefas.

Esta revisão é exclusivamente documental. As tarefas de implementação continuam pendentes e exigem autorização explícita individual, conforme `specs/README.md`.

## 2. Evidências verificadas

| Área | Estado observado | Implicação |
|---|---|---|
| Stack | `pom.xml`: Java 21, Spring Boot 3.3.5, Spring Security, Spring Data JPA, Flyway e Testcontainers. | Preservar a stack e os limites do monólito modular. |
| Bootstrap atual | `DataInitializer.java` cria `admin@platform.local` com `Admin@123` no startup, sem profile/guarda explícita. Registro comum força `Role.USER`; `PasswordEncoder` atual é BCrypt. | Remover seed do startup; não abrir o fluxo público de cadastro para ADMIN. |
| Usuário | `User` armazena e-mail único, hash, papel e UUID. `UserRepository` suporta busca por e-mail case-insensitive. | O fluxo one-shot deve tratar conflito de e-mail e identidade ADMIN sem redefinir senha. |
| Datasource/Flyway | `application.yml` usa defaults `postgres` para usuário e senha. Flyway é habilitado no mesmo Spring Boot startup, sem datasource separado. `ddl-auto` é `validate`. | Mudar só `spring.flyway.user/password` não retira a credencial privilegiada do processo da API. |
| Compose | PostgreSQL e API usam usuário `postgres` e `${DB_PASSWORD:-postgres}`. | Separar papéis e remover fallback requer alteração coordenada de Compose e configuração. |
| Migrations | Diretório real é `src/main/resources/db/migration/`. V1 executa `CREATE EXTENSION IF NOT EXISTS pgcrypto`; migrations posteriores criam estruturas e auditoria append-only. | Investigar permissão da extensão, ownership e grants; migrations já aplicadas permanecem imutáveis. |
| Testes | Testcontainers usa PostgreSQL 16 e `@ServiceConnection`; os usuários configurados no container atualmente atuam como superusuários. | Os testes atuais não provam privilégio mínimo; será necessário criar papéis/conexões independentes nos testes da iniciativa. |
| Container | `Dockerfile` não declara `USER`; runtime usa o usuário padrão root. Portas da aplicação/management são 8080/8081. | Validar UID real e permissões de runtime, não apenas a instrução Docker. |
| Segredos | `.env.example` existe; `.env` é ignorado pelo Git e não foi lido. O exemplo contém credenciais demonstrativas, inclusive `DB_PASSWORD=postgres`. | Substituir exemplos por placeholders não utilizáveis e documentar fontes protegidas. |
| CI | `.github/workflows/ci.yml` executa Maven com Java 21; não foi identificado smoke check de imagem. | Avaliar cobertura de container sem presumir que já exista. |

## 3. Decisões aprovadas

Estas decisões foram formalmente aprovadas pelo usuário em “SDD 006 — Registro e validação das decisões arquiteturais aprovadas”. Elas definem o desenho a seguir, sem significar que ele já foi implementado.

### D-01 — `bootstrap=command`

Provisionar o primeiro administrador por comando administrativo explícito, independente de requisições HTTP. O startup normal não cria ADMIN e não há credencial fixa. O comando exige autorização operacional e entrada segura de credenciais; não depende de administrador previamente cadastrado.

### D-02 — `ambientes=explicit`

O comando pode ser usado em qualquer ambiente autorizado, inclusive desenvolvimento e produção, somente quando explicitamente solicitado. Nenhum profile ou startup executa bootstrap automaticamente. A ausência de ADMIN não aciona provisão implícita.

### D-03 — `reexecução=idempotent`

Reexecuções não redefinem senhas, alteram ADMIN existente ou criam duplicatas. Execuções simultâneas devem ser seguras. O resultado é explícito e operacionalmente auditável, sem credenciais nos logs. A regra aprovada para outro ADMIN está em DP-01.

### D-04 — `banco=separate`

Separar runtime da API, migration e provisionamento administrativo do banco. Runtime não é superusuário e tem privilégios mínimos. Flyway usa identidade própria, e credenciais privilegiadas não ficam desnecessariamente disponíveis ao processo persistente da API. Não basta configurar credenciais Flyway distintas no mesmo processo de startup.

### D-05 — `container=nonroot`

A imagem executa como usuário/grupo não privilegiados. A validação deve comprovar usuário efetivo, leitura/execução dos artefatos, acesso aos temporários necessários e compatibilidade com a JVM; a presença de `USER` no Dockerfile, isoladamente, não comprova o requisito.

### Registro complementar de decisões aprovadas

Os IDs DP-01 a DP-06 foram aprovados pelo usuário em “SDD — Iniciativa 006: consolidar decisões pendentes e preparar implementação”. São decisões, não pendências:

| ID | Decisão aprovada | Regra registrada |
|---|---|---|
| DP-01 | ADMIN diferente já existente | Recusar criação; não alterar, promover, rebaixar ou redefinir contas existentes. ADMIN com mesmo e-mail é no-op sem redefinir senha; USER com mesmo e-mail é conflito. |
| DP-02 | Senha inicial | Entrada interativa em terminal, sem eco, via stdin; não usar argumento de processo nem registrar. Bootstrap automatizado não está aprovado. Se for necessário, registrar decisão operacional específica antes de definir qualquer canal não interativo; secret manager novo está fora desta iniciativa. |
| DP-03 | Flyway | Job/processo one-shot separado da API; investigar modo mais compatível com Spring Boot/Flyway e provar privilégios de migrations, incluindo `pgcrypto`. |
| DP-04 | Banco existente | Transição versionada e forward-only, com inventário, preservação de dados, verificação e recuperação operacional documentada; sem rollback automático de migrations. |
| DP-05 | Identidade bootstrap | Role exclusiva, distinta de migration/runtime; credencial não chega à API; uso restrito à operação autorizada e privilégios mínimos verificáveis. |
| DP-06 | Usuário container | UID/GID numéricos estáveis e não privilegiados, comprovados em execução da imagem. |

## 4. Requisitos funcionais e não funcionais

### RF-01 — Bootstrap sob demanda

Somente um comando explicitamente invocado pode provisionar o primeiro ADMIN. O startup da API e os perfis/ambientes não executam bootstrap automaticamente. A API normal não depende de variáveis, credenciais ou configuração de bootstrap; a ausência delas não pode impedir seu startup.

### RF-02 — Credenciais

O operador fornece nome e e-mail e digita a senha interativamente em terminal, com entrada sem eco pelo stdin. A senha não pode ser passada como argumento de processo nem aparecer em logs, código ou arquivos versionados. Bootstrap automatizado não está aprovado. O comando valida os dados com regras compatíveis com o cadastro existente e usa o `PasswordEncoder` atual.

E-mail deve seguir a normalização atual do registro: `toLowerCase(Locale.ROOT)`, consulta de duplicidade sem distinção de caixa e constraint de unicidade existente. Não introduzir `strip()` nem outra normalização.

### RF-03 — Identidade e idempotência

O comando não deve redefinir credenciais nem modificar contas existentes. Se o e-mail normalizado solicitado já pertencer a ADMIN, retorna no-op sem comparar ou redefinir a senha. Se pertencer a USER, retorna conflito sem promoção. Se não houver ADMIN com esse e-mail, mas já existir qualquer outro ADMIN, recusa a criação e mantém todas as contas intactas. O contrato define resultado explícito para criação, no-op, conflito e falha; texto e código de saída são detalhados no contrato técnico do comando.

### RF-04 — Atomicidade e concorrência

Verificação e criação ocorrem na mesma transação, sob coordenação PostgreSQL que serialize as tentativas concorrentes. Uma disputa não pode resultar em dois primeiros ADMINs, atualização parcial ou alteração de senha existente. A estratégia concreta deve ser provada com transações independentes em PostgreSQL real.

### RF-05 — Separação de papéis de banco

Provisionamento do banco, migration e runtime usam identidades separadas. API não recebe credencial administrativa ou de migration. Runtime mantém `ddl-auto: validate`, sem criar/alterar schema, papéis ou bancos.

### RF-06 — Privilégios runtime

Os grants são derivados do SQL e dos fluxos atuais. Runtime deve permitir o cadastro normal de usuários `USER`, inclusive escrita necessária em `users`, mas o PostgreSQL deve negar à role runtime a criação de usuários `ADMIN` e a promoção de usuários existentes para `ADMIN`. Runtime também não pode alterar nem desabilitar os mecanismos que impõem essa restrição. As operações normais de vínculo Customer e INSERT de auditoria permanecem permitidas; runtime não pode atualizar/remover auditoria. Não conceder DDL, `DELETE` ou `TRUNCATE` por conveniência. O mecanismo de proteção permanece aberto para investigação entre trigger, RLS, função controlada ou equivalente e deve ser provado com PostgreSQL/Testcontainers.

### RF-07 — Migrations e transição de ambientes

Flyway executa em job/processo one-shot separado da API normal. T-03 investiga a opção compatível com Spring Boot/Flyway e prova executor, origem de credenciais e privilégios para V1–V4 em PostgreSQL 16 descartável, incluindo a instalação ou o pré-provisionamento de `pgcrypto`. Essa validação não comprova compatibilidade com bancos existentes. T-05 planeja e valida a transição forward-only de bancos existentes, preservando dados e histórico Flyway, sem reescrever migrations aplicadas; inclui pré-condições, verificações e recuperação operacional documentada.

### RF-08 — Configuração por ambiente

Configuração reutilizável exige segredos explícitos e não usa senhas fracas como fallback. Testes podem usar credenciais efêmeras do Testcontainers. Documentação diferencia desenvolvimento, testes, Compose e produção e identifica quais segredos cada processo recebe.

### RF-09 — Container não privilegiado

Imagem final executa com UID diferente de zero e permissões mínimas. Aplicação lê JAR, truststore/certificados e configuração; usa somente os diretórios temporários exigidos e as portas atuais não privilegiadas. Verificar startup e usuário efetivo na imagem construída.

### RF-10 — Segredos e operação

Segredos não são versionados nem registrados. Procedimentos de bootstrap, migrations e transição de ambiente existente são reproduzíveis, autorizados e não destrutivos. `.env.example` contém apenas placeholders inequivocamente inutilizáveis.

## 5. Critérios de aceitação

- **CA-01:** startup normal não cria ADMIN, não exige configuração/credencial de bootstrap e não falha por sua ausência; nenhum segredo administrativo fixo permanece no código/configuração.
- **CA-02:** comando explícito cria o primeiro ADMIN em transação; mesmo ADMIN/e-mail é no-op sem redefinição; USER com mesmo e-mail e existência de ADMIN diferente são conflitos sem alteração de contas ou duplicidade. E-mail usa `toLowerCase(Locale.ROOT)`, consulta case-insensitive e constraint de unicidade atual, sem `strip()` adicional.
- **CA-03:** nenhum ambiente executa bootstrap automaticamente; ausência de ADMIN não aciona provisão implícita e o comportamento de operação está documentado.
- **CA-04:** senha é digitada em terminal sem eco pelo stdin; validação/hash usam convenções existentes; senha não aparece em argumentos, logs ou artefatos versionados. Execução não interativa permanece fora do escopo aprovado.
- **CA-04a — Resultados do comando:** criado: sucesso, saída `0`; ADMIN solicitado já existe: no-op sem alteração, saída `0`; outro ADMIN ou USER com e-mail solicitado: conflito sem alteração, saída `3`; argumentos/modo/entrada inválidos: falha de uso/validação, saída `2`; falha operacional: falha com rollback, saída `1`. Mensagens distinguem os resultados sem revelar senha/hash, SQL interno, stack trace ou credenciais.
- **CA-05:** runtime, migration e provisionamento são identidades distintas; processo normal da API não recebe credenciais privilegiadas.
- **CA-06:** Flyway aplica migrations em PostgreSQL limpo com identidade não superusuária; extensão `pgcrypto` funciona pela estratégia aprovada.
- **CA-07:** API inicia sem configuração de bootstrap, com papel runtime e `ddl-auto: validate`; operações suportadas, inclusive registro normal de USER, passam com os grants definidos.
- **CA-08:** PostgreSQL permite à role runtime inserir usuários USER e realizar vínculo Customer/auditoria, mas nega criar ADMIN ou promover USER a ADMIN; runtime não consegue alterar/desabilitar essa proteção, executar DDL, criar banco/papéis, apagar/truncar dados ou alterar/remover auditoria.
- **CA-09:** T-03 comprova roles, migrations e privilégios em banco descartável; T-05 comprova a transição segura de bancos existentes, preservando dados, integridade referencial, ownership aplicável e estado/histórico Flyway, sem editar migrations históricas.
- **CA-10:** configurações reutilizáveis falham de forma clara se faltarem segredos, sem usar senha fraca nem expor valores no erro.
- **CA-11:** imagem final executa como UID não zero e acessa somente arquivos/diretórios necessários; comprovação ocorre em execução da imagem.
- **CA-12:** README e `.env.example` documentam bootstrap, papéis, migrations, variáveis e gestão externa de segredos sem segredos reais.
- **CA-13:** testes comprovam bootstrap, repetição/conflitos/concorrência, grants com PostgreSQL Testcontainers, migrations, runtime e usuário não root.
- **CA-14:** regressão pertinente passa; relatório separa verificações executadas de propriedades não exercitadas.

## 6. Matriz de compatibilidade dos critérios

| Critério | Comportamento exigido | Decisão | Situação | Ajuste documental | Evidência futura |
|---|---|---|---|---|---|
| CA-01 | Sem ADMIN automático, segredo fixo ou configuração de bootstrap no runtime | D-01, D-02 | Compatível | Explicita independência do startup em relação à configuração de bootstrap | Start sem bootstrap variables/role; ausência de seed |
| CA-02 | Criação inicial e semântica de repetição/conflitos; e-mail normalizado | D-01, D-03, DP-01 | Compatível | Define regra de e-mail e resultados por cenário | Testes de comando e banco; normalização; hash imutável; corrida |
| CA-03 | Sem bootstrap implícito em qualquer ambiente | D-02 | Compatível | Removida restrição implícita a dev | Execução de API com perfis/configuração por ambiente |
| CA-04 | Validação, hash, entrada segura e resultados de saída | D-01, D-02, DP-02 | Compatível | Registra stdin interativo, códigos e mensagens seguras | Testes de entrada/saída; inspeção de logs/args/artefatos |
| CA-05 | Credenciais/identidades separadas | D-04 | Compatível | Distingue etapa/processo da configuração Flyway | Inspeção de env/config do processo API e conexões reais |
| CA-06 | Flyway não superusuário em banco vazio, inclusive pgcrypto | D-04, DP-03 | Compatível, sujeito a prova técnica | Job one-shot aprovado; executor e privilégio de extensão a comprovar | Testcontainers PostgreSQL 16 com role de migration |
| CA-07 | API sem configuração bootstrap, runtime+validate, fluxos normais incluindo registro USER | D-01, D-04 | Compatível | Inclui ausência de bootstrap config e registro USER | Subir API e exercitar registro/negócio com role runtime |
| CA-08 | Runtime registra USER e opera vínculo/auditoria, mas não cria/promove ADMIN nem desativa proteção | D-04, DP-05 | Compatível; requer prova PostgreSQL | Substitui proibição absoluta de INSERT por escrita limitada pelo banco | Testcontainers: USER permitido; ADMIN insert/update e alteração da proteção negados |
| CA-09 | Migração segura de bancos existentes | D-04, DP-04 | Compatível | Transição versionada, forward-only e com recuperação aprovada em T-05; T-03 valida apenas banco descartável | Clone/fixture com dados antes/depois e runbook exercitado em T-05 |
| CA-10 | Sem fallback inseguro | D-04 | Compatível | Especificado para configs reutilizáveis | Teste de configuração incompleta e logs sem valores |
| CA-11 | Container efetivamente não root | D-05 | Compatível | Exige prova em runtime, não só Dockerfile | Smoke check da imagem e UID/GID observados |
| CA-12 | Documentação sem segredos e por ambiente | D-01–D-05 | Compatível | Requer passo operacional e mapa de credenciais | Revisão documental e varredura de placeholders |
| CA-13 | Testes de bootstrap, grants, migrations e container | D-01–D-05 | Compatível | Testcontainers atual não basta por usar usuário elevado | Testes com papéis/conexões separados e imagem real |
| CA-14 | Regressão com resultados verdadeiros | D-01–D-05 | Compatível | Estado atual não é declarado como validado | Execução CI/Maven e relatório por cenário |

Nenhum critério conflita com D-01–D-05 ou DP-01–DP-06. CA-06 ainda exige prova técnica do executor Flyway e da permissão `pgcrypto`; isso é validação de implementação, não decisão arquitetural pendente.

## 7. Riscos e mitigação

| Risco | Mitigação proposta | Validação futura | Dependência |
|---|---|---|---|
| R1 — Sem ADMIN preexistente | Invocação one-shot autorizada sem JWT/ADMIN; role bootstrap exclusiva e stdin interativo sem eco. | Executar comando sem ADMIN e conferir criação e ausência de segredo na saída/log. | T-02; definir o modo do comando e proteger role/entrada. |
| R2 — Reexecução concorrente | Uma transação com lock PostgreSQL transacional ou mecanismo equivalente; regra por e-mail e recusa de outro ADMIN aprovadas. | Duas conexões independentes; uma cria e outra conclui no-op/conflito; uma conta e hashes intactos. | T-02; prova de coordenação em PostgreSQL real. |
| R3 — Flyway e runtime | Job/processo one-shot separado; segredo migrator somente no job; runtime sem DDL. | Testar migrations e runtime com conexões distintas e operações permitidas/negadas. | T-03; selecionar executor e validar `pgcrypto`. |
| R4 — Ambientes existentes | Inventário, transição versionada forward-only, preservação dos dados, verificações e recuperação documentada; sem rollback automático de migrations. | Exercitar procedimento em clone/fixture e comparar dados, ownership e estado Flyway antes/depois. | T-05; usa definições e mecanismos de privilégio estabelecidos em T-03. |
| R5 — Testcontainers | Roles separadas; identidade de setup restrita; migrator aplica Flyway; aplicação opera como runtime. | Subir banco limpo e validar contexto, grants e falhas com conexões independentes. | T-03; adaptar `@ServiceConnection` sem reduzir cobertura. |
| R6 — Docker nonroot | UID/GID numéricos estáveis; permissões mínimas e temporários explicitamente concedidos. | Na imagem executada, observar UID/GID, iniciar JVM, acessar PostgreSQL, verificar health e escrita limitada. | T-04; compatibilidade do UID com filesystem/volumes. |

## 8. Detalhes técnicos a validar durante implementação

DP-01 a DP-06 estão aprovadas e não são bloqueios decisórios. Permanecem atividades de engenharia, que devem ser resolvidas com evidência e sem alterar os contratos aprovados:

1. Comparar job one-shot baseado na dependência Flyway do projeto com CLI/plugin, escolhendo integração que use as migrations versionadas sem iniciar a API com segredo privilegiado.
2. Verificar em PostgreSQL 16 os privilégios necessários para V1 instalar `pgcrypto`; preferir pré-provisionamento da extensão se isso mantiver o migrator sem superuser.
3. Definir grants mínimos da role exclusiva de bootstrap sem permitir criação/promoção de ADMIN pela role runtime. O runtime precisa escrever em `users` para registrar USER; a restrição deve ser imposta no PostgreSQL. Não conceder nem negar INSERT indiscriminadamente sem provar esse contrato.
4. Implementar mensagens e códigos de saída do comando conforme CA-04a e verificar que não expõem dados sensíveis.
5. Bootstrap interativo é o contrato aprovado. Se surgir necessidade de automação, registrar decisão operacional específica antes de definir entrada não interativa; secret manager está fora do escopo aprovado.
6. Em T-05, especificar e validar inventário, pré-condições, backup e recuperação para bancos existentes; não assumir rollback automático de migrations nem retorno a superusuário. T-03 limita-se à validação em PostgreSQL descartável.
7. Confirmar diretórios em que JVM/aplicação escrevem e provar UID/GID estáveis sem permissões amplas.

A política de autorização JWT quando um ADMIN persistido é rebaixado permanece fora do escopo desta iniciativa; não modificar nem inferir comportamento nessa área.
## 9. Referências

- `AGENTS.md`, `specs/README.md`
- `specs/001-observability/`, `specs/002-authorization-policy/`, `specs/005-user-customer-link-management/`
- `DataInitializer.java`, pacote `auth`, `SecurityConfig`, `application.yml`, `application-test.yml`, migrations, `Dockerfile`, `docker-compose.yml`, `.env.example`, `pom.xml`, testes Testcontainers e CI.

