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

#### T-05.1 — Estados iniciais aceitos e classificação

O escopo inicial da transição fica limitado aos estados abaixo, sempre em PostgreSQL 16 e após inventário aprovado. A classificação é fechada: ausência de uma evidência exigida, divergência não explicada ou objeto não classificado resulta em recusa. T-05.1 define o contrato; não implementa o classificador nem autoriza transição de banco.

| Estado observado | Classificação | Pré-condições / motivo |
|---|---|---|
| V1–V4 aplicadas com sucesso; histórico e checksums correspondem aos arquivos versionados; objetos da aplicação pertencem integralmente a um único owner legado identificado | **Suportado somente após pré-condições específicas** | Deve haver backup restaurável confirmado, PostgreSQL 16, schema/objetos limitados ao conjunto inventariado, `pgcrypto` compatível e nenhuma role/ACL ou dependência fora do inventário. A transição poderá aplicar V5 como `app_migrator`, após takeover de ownership explicitamente planejado e validado em fixture. |
| V1–V5 aplicadas com sucesso; histórico e checksums íntegros; objetos da aplicação pertencem integralmente a um único owner legado identificado | **Suportado somente após pré-condições específicas** | Mesmas condições do estado anterior. Trigger/função de auditoria V4 e proteção ADMIN V5 devem corresponder às definições versionadas; não se reaplica migration. A transição ajusta ownership/grants e valida as proteções existentes. |
| Provisionamento parcial, com algumas roles-alvo já existentes | **Não suportado; diagnóstico manual** | O script atual cria roles sem idempotência e não reconcilia atributos, memberships, credenciais nem grants parciais. Não completar nem alterar roles automaticamente nesta primeira faixa. |
| Histórico Flyway ausente | **Não suportado; diagnóstico manual** | Não é possível distinguir banco legado compatível de schema criado fora do Flyway. Não inferir baseline nem criar histórico automaticamente. |
| Checksum divergente, migration malsucedida, versão desconhecida ou sequência incompleta | **Não suportado; diagnóstico manual** | Exige identificar a causa e reconciliar schema/histórico fora do procedimento automático. Não executar `repair`, baseline ou edição de histórico durante a classificação. |
| Ownership misto, desconhecido ou compartilhado com objetos não relacionados | **Não suportado; diagnóstico manual** | A transferência ampla pode atingir objetos fora da aplicação; não usar `REASSIGN OWNED` como atalho. |
| Objetos adicionais, extensões/dependências não previstas ou objetos da aplicação fora de `public` | **Não suportado; diagnóstico manual** | A faixa inicial cobre somente o schema/migrations inventariados. Expandir suporte exige análise e aprovação documental, fixture representativa e atualização desta matriz. |

##### Contrato do inventário e classificação

O inventário futuro deve produzir evidências sem consultar, extrair ou registrar senhas e segredos:

- Identidade e versão do servidor PostgreSQL; compatibilidade exata com 16.
- Linhas de `flyway_schema_history`: versões, estados de sucesso, checksums e descrições; comparação com V1–V5 esperadas.
- Existência e atributos das roles relacionadas, memberships e grants efetivos. Registrar atributos e nomes, nunca hashes de senha ou material de autenticação.
- Owners e ACLs de schemas e de todos os objetos: tabelas, índices, constraints, sequences, views, funções e triggers; dependências e objetos referenciados.
- Extensões, versões, schemas e owners, incluindo `pgcrypto`.
- Definições efetivas e owners das funções e triggers V4/V5; comparação estrutural com SQL versionado, não apenas presença nominal.
- Constraints, índices e dependências de chaves estrangeiras; contagens por tabela e verificações de integridade referencial em fotografia consistente.
- Configuração efetiva e identidades usadas por todos os consumidores, jobs e réplicas da base; confirmar que a API antiga não mantém credencial privilegiada no corte.
- Evidência de backup concluído e de restauração verificável, sem incluir credenciais nos registros.

##### Aborto obrigatório

Recusar a transição se backup restaurável não estiver confirmado; PostgreSQL não for 16; histórico Flyway estiver ausente, desconhecido, com checksum divergente, migration malsucedida ou sequência não prevista; houver roles com atributos/memberships inesperados; owners, ACLs, objetos ou dependências não puderem ser classificados; V4/V5 estiverem ausentes ou divergirem; houver objeto extra não aprovado; API antiga ainda usar credenciais privilegiadas no corte; ou não for possível comprovar integridade e comparar os dados antes/depois. A classificação desconhecida falha fechada.

##### Evidências exigidas para aprovar um estado

Registrar relatório do inventário antes/depois, classificação e motivo, versão PostgreSQL, histórico Flyway, owners/ACLs e dependências, extensões, definições V4/V5, checksums/contagens de dados e integridade, identidade efetiva de cada consumidor e confirmação de backup restaurável. A aprovação de um estado não aprova execução em outro banco nem amplia a matriz.

##### Dependências

T-05.2 deverá implementar inventário somente leitura e validação da matriz, sem transição. T-05.3 poderá preparar fixtures e testar a transição nos estados aprovados, exclusivamente em PostgreSQL 16 descartável via Testcontainers, após autorização própria. Nenhuma dessas etapas altera os limites aprovados em DP-03–DP-05.

#### T-05.3 — Análise de viabilidade do procedimento versionado (2026-11-06)

A implementação executável não deve avançar enquanto os contratos abaixo não estiverem resolvidos. A análise da T-05.3 confirmou que os dois estados aprovados exigem operações iniciais distintas: V1–V4 pode não ter roles alvo, enquanto V1–V5 exige que as três roles já existam com atributos e memberships aprovados. O inventário T-05.2 é somente leitura e não comprova que a identidade de provisionamento pode criar roles, nem que ela é membro autorizado do owner legado e de `app_migrator` para transferir ownership sem superuser.

Também falta definir um checkpoint durável que seja atômico com cada etapa. Um arquivo externo pode ficar atrasado ou adiantado em relação ao commit PostgreSQL em uma queda; um registro dentro do banco exige um objeto de controle, que o inventário atual classifica como objeto/schema adicional e rejeita. A retomada de ownership parcial precisa reconhecer somente o prefixo de etapas comprovadamente aplicado, sem tratar ownership misto arbitrário como suportado. O contrato atual não define essa prova nem a identidade autorizada para gravar o checkpoint.

Antes da implementação, uma revisão técnica deve especificar: (1) canal seguro para credenciais das roles criadas apenas no estado V1–V4, sem redefinir credenciais das roles V1–V5 existentes; (2) capacidades exatas e memberships da identidade de provisionamento não-superuser; (3) local, formato, durabilidade, controle de concorrência e verificação do checkpoint em conjunto com cada commit; (4) regra fail-closed para reconciliar uma falha entre etapas; (5) como inventário e classificação reconhecem o estado concluído e os estados parciais autorizados sem ampliar a matriz T-05.1; e (6) contrato de grants de sequências/colunas e ownership final por tipo de objeto. Essa revisão não autoriza acesso a banco persistente e deve continuar sendo exercitada apenas em PostgreSQL 16 descartável via Testcontainers.

#### T-05.3-B — Refinamento proposto de D-06 a D-10 (aguardando aprovação)

As opções abaixo são recomendações técnicas decorrentes da inspeção de V1–V5, `provision-postgres-roles.sql`, T-03 e do inventário T-05.2. Não são decisões aprovadas. T-05.3 permanece incompleta até aprovação explícita e validação posterior em PostgreSQL 16 descartável.

##### T-05.3-C — Auditoria documental da suficiência do inventário (2026-11-06)

Esta auditoria inspecionou `DatabaseInventoryCommand`, seus testes de integração, `inventory-usage.md`, script de provisionamento, migrations V1–V5 e implementações/provas de bootstrap e privilégios. As tabelas abaixo descrevem o comportamento implementado hoje; o contrato futuro proposto não é comportamento existente nem aprovação de D-06–D-10.

##### Atributos de roles

Origem: `pg_roles` (visão de `pg_authid` sem material de senha). A consulta produz linhas apenas para `app_migrator`, `app_runtime` e `app_bootstrap`; não consulta a identidade de inventário nem provisionadora. Campo ausente significa não coletado; SQL NULL é valor nulo/default ou role inexistente conforme o campo; erro geral vira `UNKNOWN/NOT_VERIFIABLE`, sem status individual por atributo.

| Atributo | Coleta/exibição atual | Classificação atual | Risco; regra proposta para provisionadora |
|---|---|---|---|
| `rolsuper` | Sim nas roles-alvo, `superuser`. | Exige false nelas. | Provisionadora não verificada. Exigir false e status `VERIFIED`; nulo/desconhecido aborta. |
| `rolcreaterole` | Sim, `createrole`. | Exige false nas roles-alvo. | Não prova capacidade nem excesso da provisionadora. Exigir false em V1–V5; em V1–V4 permitir true somente se criar roles for aprovado, necessário e limitado à janela. Ausente/nulo aborta. |
| `rolcreatedb` | Sim, `createdb`. | Exige false nas roles-alvo. | Provisionadora não verificada; exigir false, ausência/nulo aborta. |
| `rolreplication` | Sim, `replication`. | Exige false nas roles-alvo. | Provisionadora não verificada; exigir false, ausência/nulo aborta. |
| `rolbypassrls` | **Não coletado.** | Não participa. | Pode contornar RLS. Exigir false para provisionadora e roles relevantes; sem prova, abortar. |
| `rolinherit` | Sim, `inherit`. | Exige false nas roles-alvo. | Não substitui `inherit_option` do vínculo PG16 nem prova capacidades transitivas. Exigir valor aprovado e analisar vínculos. |
| `rolcanlogin` | Sim, `login`. | Exige true nas roles-alvo existentes. | Exigir LOGIN apenas na janela autorizada, com plano verificável de desabilitação/revogação após operação; estado inesperado aborta. |
| `rolvaliduntil` | **Não coletado.** | Não participa. | Não comprova validade. Exigir valor e interpretar NULL como “sem expiração”, não desconhecido; para identidade temporária, NULL aborta. Prazo não substitui revogação e pode não ser imposto pelo método de autenticação. |
| `rolconfig` | **Não coletado.** | Não participa. | Configuração por role pode alterar defaults/search_path; exigir valor contra allowlist (preferência vazia), incluindo settings por database; desconhecido/diferente aborta. |

A validação atual das roles-alvo exige LOGIN, `NOINHERIT`, sem superuser/createdb/createrole/replication e nenhuma membership direta. Isso é somente perfil parcial dessas roles. Para role inexistente, `role_exists=false` e outros campos SQL NULL não são evidência segura. Erros de visibilidade/consulta resultam em UNKNOWN, mas não há diagnóstico por campo.

##### Memberships

| Informação | Situação atual |
|---|---|
| Membro, role concedida | Lê `pg_auth_members` somente para `app_*` como membro; mostra a role concedente. A provisionadora não é alvo. |
| `admin_option`, `inherit_option`, `set_option` | Exibe as três opções dos vínculos diretos PG16. Validador rejeita qualquer vínculo direto das roles-alvo. |
| Cadeias indiretas | Não há consulta recursiva nem fechamento transitivo; não prova ausência de caminho para role privilegiada. |
| Alcance/capacidades | Não calcula `SET ROLE`, privilégios herdados ou poder de conceder memberships em cadeia. |

Analisar somente vínculos diretos é insuficiente para a provisionadora. Contrato futuro: grafo completo de vínculos com member, granted role, grantor e opções PG16; fechos transitivos separados para assumir identidade (`SET`), herdar (`INHERIT`) e administrar/conceder vínculos (`ADMIN`). Reportar caminho até superuser, `CREATEROLE`, `CREATEDB`, replication, `BYPASSRLS`, roles da aplicação, owner legado/final e qualquer owner amplo. Se a identidade leitora não enxergar o grafo completo, classificar NOT_VERIFIABLE e recusar. `NOINHERIT` ou ausência de vínculo direto não prova ausência de alcance transitivo.

##### Privilégios efetivos

| Escopo | Coleta atual | O que não prova |
|---|---|---|
| Database/schema | `has_database_privilege` para CONNECT/CREATE/TEMP e `has_schema_privilege` para USAGE/CREATE das roles-alvo no database/schema public; ACL textual dos schemas. | Provisionadora, owner/ACL do database, outros schemas (extras recusados), origem direta/herdada/PUBLIC/ownership. |
| Tabelas | `has_table_privilege` de sete operações para roles-alvo em seis tabelas; `relacl` em objetos selecionados. | Colunas, grantor/origem, provisionadora, expansão de defaults; escopo é limitado. |
| Sequences | `has_sequence_privilege` USAGE/SELECT/UPDATE nas sequences public para roles-alvo. | Provisionadora e fonte do privilégio; sequences não são esperadas em V1–V5 e extras devem abortar. |
| Funções | ACL bruta em funções/procedures públicas; PUBLIC EXECUTE verificado especificamente nas funções V4/V5. | Matriz efetiva geral, herança, defaults/grantor e provisionadora. |
| `PUBLIC`/defaults | ACL textual; caso específico de função V5. | ACL nula pode significar default, não ausência; `pg_default_acl` não coletado. |
| Atributos/ownership | Alguns atributos de roles-alvo e owners de subset de objetos. | Atributos administrativos e ownership conferem poderes sem entrada ACL e não são decompostos. |

`has_*_privilege` é evidência do efetivo para principal/operação/objeto consultados, mas não sua fonte. ACL ausente não prova efetivo zero: devem ser considerados defaults, PUBLIC, membership, owner e atributos administrativos. Contrato confiável exige ACL normalizada (grantee/grantor, grant options, PUBLIC e defaults) junto à matriz efetiva da provisionadora e roles-alvo, atribuindo fonte/caminho. Qualquer privilégio extra ou fonte inexplicada bloqueia.

##### Ownership, ACL e cobertura de objetos

| Classe | Descoberta/owner/ACL atuais | Limite e critério de aborto |
|---|---|---|
| Database | Não coleta owner/ACL em `pg_database`; apenas efetivos limitados. | Exigir owner, ACL expandida, OID/identidade; divergência ou grant não classificado aborta. |
| Schemas | Todos não-toast, owner e ACL textual; schema extra bloqueia. | Default/effective ACL não expandida. `platform_transition` hoje é schema extra e recusado. |
| Tabelas/relations | `pg_class` public: tabelas, partitions, views/materialized, sequences, foreign tables; owner/relacl, exclui Flyway history. | Não abrange grants por coluna/grantor nem classifica integralmente propriedades; qualquer relation extra aborta. |
| Índices | `relkind=i`, owner/ACL e lista de nomes. | Nome não prova definição/vínculo/validade; qualquer divergência estrutural aborta. |
| Constraints | Em public, nome/relação/tipo/validada/definição. | Sem owner ou ACL independente; exige definição, vínculo, OID/relação e estado esperados. |
| Sequences | Descobertas e efetivos para app roles. | Nenhuma esperada em V1–V5; qualquer sequência inicial extra aborta. |
| Funções/procedures | `pg_proc` public fora de extensão, assinatura/kind/owner/ACL; corpos/proteções específicos comparados. | Não valida genericamente `prosecdef`, configuração/search_path, linguagem/segurança nem ACL efetiva completa. Overload ou definição/propriedade extra aborta. |
| Triggers | Não internos em public; owner inferido da tabela; proteção compara definição/eventos/enabled. | Outros schemas e triggers internos não analisados; qualquer trigger fora do conjunto comprovado aborta. |
| Types | Enum/domain em public não pertencente a extensão, owner. | Types base/composite/range/multirange e ACL não cobertos; qualquer type não explicado aborta. |
| Extensões | Nome/versão/schema/owner; exige pgcrypto. | Membros/ACLs/dependências não enumerados integralmente; exigir conjunto e membros exatos, divergência/extra aborta. |
| Dependências | `pg_depend` iniciado em objetos public, resolve class/proc/type e usa heurística por namespace/pertencimento a extensão. | Não é grafo completo de todas as classes, direções e objetos internos; OID/classe não resolvido ou caminho não classificado aborta. |
| Flyway history | Versões, descrição, checksum, sucesso, installed_by e estado; excluído de objetos comuns. | Owner/ACL/estrutura integral não comprovados; exigir verificação própria da tabela e integridade do histórico. |
| Objetos internos PostgreSQL | Alguns podem aparecer como referências; sem inventário geral de objetos compartilhados/internos. | Não podem ser alvo de alteração. Se não for possível excluí-los do conjunto de operações, abortar. |

##### Matriz de suficiência do inventário

Severidades: **bloqueadora** impede aprovação de D-06–D-10 ou prova de transição; **importante** impede autorização operacional/conclusão, ainda que não impeça classificar uma fotografia inicial; **informativa** é contexto explicitamente fora da inferência automatizada. Um item importante torna-se bloqueador se necessário para aquele estágio.

| Informação necessária | Coletada atualmente? | Classificada? | Necessária para D-06–D-10? | Lacuna/severidade | Critério de aceite futuro |
|---|---|---|---|---|---|
| Identidade PostgreSQL/database | Versão, nome e session user; sem fingerprint estável/owner database | Parcial | D-06, D-10 | Bloqueadora | Endpoint lógico, database OID/nome/owner e principal efetivo verificados. |
| Atributos das app roles | Parcial; faltam bypassrls, validuntil, rolconfig | Parcial p/ T-05.1 | D-07, D-09 | Bloqueadora | Todos os atributos necessários presentes com status individual verificado. |
| Identidade/atributos da provisionadora | Não | Não | D-07 | Bloqueadora | Principal exato e perfil completo comparado à allowlist do estágio. |
| Memberships diretas | Só para app roles como membros | Parcial | D-07, D-09, D-10 | Bloqueadora | Todos vínculos com grantor e opções PG16. |
| Memberships transitivas | Não | Não | D-07, D-09, D-10 | Bloqueadora | Alcances SET/INHERIT/ADMIN e caminhos transitivos comprovados. |
| Privilégios efetivos | Parcial para app roles/operações limitadas | Sem proveniência completa | D-07, D-09, D-10 | Bloqueadora | Matriz da provisionadora/app roles com fontes direto/herdado/PUBLIC/owner/atributo. |
| ACL/defaults/grantor | ACL bruta parcial; defaults não expandidos | Não integral | D-06, D-07, D-09 | Bloqueadora | ACL normalizada por OID, grantor, opções, PUBLIC e default privilege mais efetivos. |
| Ownership | Schemas e subset de objetos public; falta database e outras classes | Parcial | D-07, D-09, D-10 | Bloqueadora | Owners/OIDs completos de cada classe alvo e classes excluídas identificadas. |
| Funções, triggers, constraints | Parcial, foco V4/V5 e public | Parcial | D-09, D-10 | Bloqueadora | Definições, propriedades, vínculos e ACLs exatos para allowlist; extras recusados. |
| Extensão/membros/dependências | Metadados extension e dependências parciais | Parcial | D-07, D-09, D-10 | Bloqueadora | Versão/schema/owner/membros/ACL/deps exatos, OIDs resolvidos. |
| Dados/integridade | Contagens consistentes e constraints validadas | Parcial | D-09, D-10 | Importante na classificação; bloqueadora para provar preservação/conclusão | Comparação antes/depois e integridade verificadas sem copiar dados pessoais. |
| Journal | Não coletado; schema extra é recusado | Não | D-06, D-10 | Bloqueadora | Estado ausente ou formato único conhecido, ACL segura, cadeia válida e efeito equivalente. |
| Consumers/backup/corte | Marcados explicitamente não verificáveis | Não | D-08, D-10 | Informativa para inventário; bloqueadora para corte | Evidências operacionais externas rastreáveis, backup restaurável e consumers sem segredo privilegiado. |
| Estado por campo/ausência | Erro geral UNKNOWN; ausência de atributo não aparece | Não | D-06 a D-10 | Bloqueadora | Status `PRESENT`, `NULL_VALUE`, `NOT_APPLICABLE`, `NOT_COLLECTED` ou `NOT_VERIFIABLE` com origem/motivo. |

##### Contrato mínimo e impacto sobre as decisões

O relatório futuro deve ter versão e fotografia consistente; identidade de server/database e principal de conexão; estado/origem por campo distinguindo valor nulo, não aplicável, não coletado e não verificável; Flyway/schema comparados às migrations; atributos de todas as roles relevantes sem material de autenticação; grafo completo de memberships/opções; ACLs normalizadas e efetivos para database/schema/relação/coluna/sequence/função com PUBLIC/defaults, grantor, herança, ownership e atributos administrativos; inventário OID/classe/nome qualificado/owner/ACL/definição/estado/dependências e classes explicitamente fora de cobertura; extensão e membros; dados/integridade; e journal conhecido/cadeia/fingerprint comparados aos efeitos, ou prova de sua ausência. Evidências operacionais (backup, distribuição e consumers) permanecem separadas de fatos catalogados.

Classificação positiva requer todos os elementos obrigatórios `VERIFIED`, sem lacunas `NOT_COLLECTED`/`NOT_VERIFIABLE`, objetos e capacidades dentro da allowlist T-05.1 e nenhuma capacidade não prevista alcançável. Journal transitório só é reconhecido se formato/cadeia conhecidos e efeitos equivalentes; não amplia estado inicial. Dado ausente não equivale a `false`; ACL ausente não equivale a privilégio zero; objeto não enumerado não equivale a inexistente. Desconhecido resulta em recusa manual.

| Decisão | Inventário atual suficiente? | Fechável documentalmente | Exige implementação/teste futuro |
|---|---|---|---|
| D-06 — Journal | **Não.** Não é reconhecido; schema extra é recusado; sem validação de cadeia/ACL/efeito. | Campos, atomicidade e fail-closed podem ser especificados. | Classificador do journal e testes PG16 ausente/legítimo/corrompido/duplicado e crash/commit. |
| D-07 — Provisionadora | **Não, bloqueadora.** A identidade nem é consultada; faltam atributos e alcance transitivo. | Perfil desejado e capacidades proibidas/condicionais. | Ampliar inventário com autorização própria; provar grafo, efetivos, CREATEROLE condicional, SET/ownership e revogação em fixture. |
| D-08 — Credenciais | **Não para corte.** Inventário não prova canal, distribuição ou autenticação consumer. | Política sem segredos, sem redefinição e recuperação externa. | Prova de canal/logging/TLS, autenticação e falhas em fronteira externa. |
| D-09 — Ownership/grants | **Não.** Evidência parcial de T-05.1 não cobre database, provisionadora nem proveniência completa. | Allowlist e abortos por objeto. | Cobertura ACL/ownership expandida e prova PG16 de transferências, grants/revokes e efetivos. |
| D-10 — Recuperação | **Não.** Sem journal nem baseline completo não prova prefixos/ausência de efeitos. | Máquina de estados e fronteiras não atômicas como proposta. | Reconciliação fail-closed e crash tests PG16 em cada etapa/fronteira. |

O inventário atual serve somente como candidato limitado aos estados T-05.1, conforme suas ressalvas; não atesta segurança da provisionadora, privilégios completos nem retomada D-06–D-10. Nenhuma decisão é aprovada por esta auditoria.

Referências PostgreSQL 16 para semântica dos atributos e visibilidade de `pg_roles`, opções/membership transitiva e privilégios inerentes a ownership/PUBLIC: [pg_roles](https://www.postgresql.org/docs/16/view-pg-roles.html), [Role Membership](https://www.postgresql.org/docs/16/role-membership.html), [System Information Functions](https://www.postgresql.org/docs/16/functions-info.html) e [Privileges](https://www.postgresql.org/docs/16/ddl-priv.html). Essas referências não alteram o achado de que o inventário atual não consulta ou classifica todos esses dados.

##### T-05.3-D — Plano de evolução do inventário (2026-11-12)

**Estado:** planejamento documental concluído; execução futura não autorizada. D-06 a D-10 continuam propostas até aprovação explícita. O escopo futuro do inventário é somente leitura e não altera dados, roles, ownership, ACLs, migrations ou journal.

Compatibilidade a preservar nas tarefas futuras: PostgreSQL 16, Java 21, Spring Boot 3.3.5, Maven e Testcontainers já usados pelo projeto; não alterar contratos da API nem migrations V1–V5. Manter runtime, migrator, bootstrap e provisionadora como identidades separadas. Inventário não reutiliza credenciais privilegiadas no processo da API.

###### Arquitetura proposta

Evoluir `DatabaseInventoryCommand` em camadas de coleta tipadas e versionadas, mantendo uma única fotografia PostgreSQL 16 em transação `REPEATABLE READ READ ONLY`. Separar: identidade/fingerprint; coleta catalogal; normalização de ACLs/grafo; avaliação de efetivos; classificação fail-closed; serialização do relatório sem segredos. Cada campo de evidência inclui origem/catálogo, escopo, valor quando seguro e estado `PRESENT`, `NULL_VALUE`, `NOT_APPLICABLE`, `NOT_COLLECTED` ou `NOT_VERIFIABLE`, mais razão para não verificação. Ausência de visibilidade ou falha SQL nunca vira `false`/aprovação.

Identidades cobertas incluem `session_user`/`current_user`, roles de aplicação, provisionadora indicada por parâmetro/configuração explicitamente não secreto, owner legado/final, roles alcançáveis no grafo e PUBLIC. O inventário não deve consultar `rolpassword`, verifiers, secrets de conexão ou dados de credencial. Para `rolconfig`/settings por role-database, avaliar as chaves/valores apenas em memória contra allowlist e nunca imprimir valores potencialmente sensíveis; relatório apresenta chave permitida e resultado da comparação, ou categoria redigida. Falha para inspecionar configuração resulta em `NOT_VERIFIABLE`.

Coleta bruta deve manter identificadores qualificados e escopo; normalização não substitui evidência. O avaliador produz uma explicação da decisão e dos caminhos de privilégio. Uma chamada `has_*_privilege` pode validar resultado efetivo de uma operação, mas não é explicação de proveniência. Todas as consultas permanecem SELECT/catálogos e verificação read-only; cada teste compara estado antes/depois e falha se o inventário mutar o banco.

##### Subtarefas futuras (cada uma requer autorização explícita própria)

Os nomes/arquivos abaixo são planejamento, não autorização de alteração. Implementação em tarefa por vez; arquivos de produção/teste previstos são os mínimos conhecidos e poderão ser refinados no plano de execução individual, sem tocar migrations V1–V5 nem configuração de produção.

###### T-05.3-D1 — Estados de coleta e atributos de roles

- **Objetivo/escopo:** modelo de evidência e status por campo; coletar os nove atributos listados na auditoria para app roles, principal de conexão, provisionadora explicitamente configurada, owners e roles relevantes. Tratar roles ausentes, NULL/default, inaplicável e sem visibilidade separadamente; classificar perfil da provisionadora por estágio V1–V4/V1–V5. Redigir `rolconfig`/settings sem valores sensíveis.
- **Arquivos previstos:** `DatabaseInventoryCommand.java`, `DatabaseInventoryCommandIntegrationTest.java`, `inventory-usage.md`.
- **Dependências:** T-05.3-C concluída; D-07 ainda proposta, por isso implementar coleta genérica não pode assumir atributos aprovados nem declarar transição elegível.
- **Testes PG16/Testcontainers:** atributos true/false; validade finita/NULL; config vazia, allowlisted e proibida com valor-canário que não pode aparecer no JSON; role ausente; privilégio insuficiente de leitura; saída não revela senha/verifier/configuração sensível; repetição read-only.
- **Aceite:** cada atributo tem origem, valor/status explícitos e regra; provisionadora é identificada sem segredo; campo invisível é bloqueador; ausência de role não se confunde com role segura; relatório não contém valores confidenciais.
- **Riscos:** visão/configuração insuficiente, settings por database omitidos, leaks em logs/JSON, significado de validade limitado ao método de autenticação.
- **Fora:** criar/alterar roles, provisionar credenciais, ampliar estados suportados ou aprovar D-07.

###### T-05.3-D2 — Grafo de memberships e capacidades transitivas

- **Objetivo/escopo:** coletar arestas member→granted role, grantor e opções `ADMIN`, `INHERIT`, `SET`; analisar fechamento transitivo em separado para SET ROLE, herança e administração de memberships, anotando caminhos para identidades privilegiadas/owners.
- **Arquivos previstos:** `DatabaseInventoryCommand.java`, `DatabaseInventoryCommandIntegrationTest.java`, `inventory-usage.md`.
- **Dependências:** D1 para estados/origem de role; requer contrato explícito de quais principals e roles de catálogo devem ser alcançáveis. Pode ser desenvolvido independentemente de D3/D4, mas consolidação aguarda ambos.
- **Testes PG16/Testcontainers:** vínculo direto aceito/negado; caminhos indiretos; SET possível/impossível comparado a tentativa real em conexão fixture; herança ligada/desligada; ADMIN OPTION; opção SET/INHERIT false; tentativa de ciclo rejeitada pelo PostgreSQL e grafo não aprovado se catálogo inesperado/inconsistente.
- **Aceite:** relatório apresenta arestas e paths; capacidades são calculadas de acordo com opção em cada elo; distinção entre identidade assumível, privilégio herdado e poder de grant; grafo incompleto ou role privilegiada inesperada bloqueia.
- **Riscos:** semântica PG16 por opção, crescimento do grafo, privilégios de leitura insuficientes, interpretação equivocada de atributo `rolinherit` isolado.
- **Fora:** alterar memberships, testar/escalar privilégios no banco alvo ou assumir que ausência de ciclo no cálculo substitui validação PostgreSQL.

###### T-05.3-D4 — Descoberta de objetos, ownership e ACLs

- **Objetivo/escopo:** construir inventário por catálogo e classe para database, schemas, relations, índices, constraints, sequences, funções/procedures, triggers, tipos, extensões/membros, Flyway history, defaults e dependências. Capturar OID mais classe/escopo, nome qualificado, owner, ACL normalizada, grantor/grantee/grant option e definição/estado relevante; identificar lacunas de visibilidade.
- **Descoberta planejada:** `pg_database` (`datdba`, `datacl`); `pg_namespace`; `pg_class`/`pg_attribute`; `pg_index`; `pg_constraint`; `pg_sequence`; `pg_proc`; `pg_trigger`; `pg_type`; `pg_extension` e seus membros por `pg_depend` (`deptype='e'`); dependências de objetos por `pg_depend` e dependências compartilhadas pertinentes via `pg_shdepend`; defaults em `pg_default_acl`; ACLs por colunas/catálogo apropriado expandidas com `aclexplode`. Flyway history e journal são relações normais cujos owners/ACL/estrutura também devem ser verificados. Objetos internos serão enumerados apenas no escopo alcançável das operações permitidas e classificados como não alteráveis; uma classe/OID não resolvida bloqueia. Validar visibilidade por role de leitura, não presumir catálogo integralmente visível.
- **Arquivos previstos:** `DatabaseInventoryCommand.java`, `DatabaseInventoryCommandIntegrationTest.java`, `inventory-usage.md`.
- **Dependências:** D1 para principals/status; estabelece base de objetos para D3/D5/D7.
- **Testes PG16/Testcontainers:** cada classe suportada; ACL explícita, nula/default, PUBLIC, ownership, extension member, dependências resolvidas/não resolvidas, partições/overloads e objetos extras. Confirmar que OID é estável apenas no ciclo de vida do objeto/database: DROP/CREATE pode reutilizar/alterar identidade; rename preserva OID mas altera nome; restauração/clonagem pode mudar OIDs. Divergência OID+identidade/definição invalida inventário aprovado.
- **Aceite:** toda classe tem cobertura afirmada/limitação; identidade composta por database fingerprint + catálogo/classe + OID/objsubid e nome qualificado; nenhuma transferência de objeto não allowlisted; item não resolvido bloqueia classificação automática.
- **Riscos:** catálogo muda por versão, objects implícitos/internos, ACL padrão, OID não global/imutável, consulta com visibilidade incompleta e custo.
- **Fora:** qualquer ALTER OWNER/GRANT/REVOKE, manipulação de migration history, extensão ou objeto interno.

###### T-05.3-D3 — Privilégios efetivos e proveniência

- **Objetivo/escopo:** combinar ACL normalizada D4, memberships D2, atributos D1 e ownership para explicar para cada principal/operação o privilégio efetivo e a proveniência: direto, herdado (com path), PUBLIC, ownership, administrativo, múltiplas origens ou não verificável. Cobrir database/schema/table/column/sequence/function e default privileges.
- **Arquivos previstos:** `DatabaseInventoryCommand.java`, `DatabaseInventoryCommandIntegrationTest.java`, `inventory-usage.md`.
- **Dependências:** D1, D2 e D4.
- **Testes PG16/Testcontainers:** mesmo efetivo por grants direto/herdado/PUBLIC/owner; grant options; default ACL; colunas; funções; capacidades administrativas; comparação da explicação ao resultado PostgreSQL `has_*_privilege` e tentativas permitidas/negadas em conexões de fixture.
- **Aceite:** efetivo e cada fonte aparecem separados; `has_*` é usado como verificação cruzada, nunca única explicação; privilégio extra, fonte não atribuível ou visibilidade parcial resulta em bloqueador.
- **Riscos:** múltiplas fontes do mesmo privilégio, grants revogáveis/encadeados, diferença entre capability role-level e objeto, defaults.
- **Fora:** mudar privilégios/roles; D-07/D-09 não ficam aprovadas pelo relatório.

###### T-05.3-D5 — Journal e checkpoints

- **Objetivo/escopo:** reconhecer estado sem journal, journal legítimo em andamento, completo, ausente após mutação, duplicado e divergente; validar estrutura/versão, transition id, fingerprint database, sequência/hash/eventos, identidade executora e cada efeito real em conjunto. O journal nunca prova etapa isoladamente.
- **Arquivos previstos:** `DatabaseInventoryCommand.java`, `DatabaseInventoryCommandIntegrationTest.java`, `inventory-usage.md`.
- **Dependências:** D1, D2, D3 e D4 e aprovação explícita de D-06/D-10 antes da respectiva implementação. Não classificar journal especial antes de contrato aprovado.
- **Testes PG16/Testcontainers:** ausência limpa; cada estado válido; cadeia quebrada, evento ausente/duplicado/reordenado, schema/coluna extra, journal de versão desconhecida, efeito sem evento, evento sem efeito, fingerprint/identity divergentes, transição completa alterada depois; conferir ACL de journal sem mutar.
- **Aceite:** apenas formatos/estados aprovados reconhecidos; journal legítimo não vira estado inicial T-05.1; estado do journal corresponde a catálogo/efeitos reais; qualquer dúvida é UNKNOWN/REJECTED; inventário não insere/atualiza lock/evento.
- **Riscos:** owner/superuser pode adulterar; hash encadeado não prova autenticidade, TOCTOU após fotografia; DDL Flyway e credenciais cruzam limites transacionais.
- **Fora:** criar journal, executar checkpoints ou transição, implementar D-06/D-10 sem aprovação.

###### T-05.3-D6 — Integridade de dados e evidências externas

- **Objetivo/escopo:** reportar verificações internas e contrato separado de evidência externa. Inventário pode contar linhas em snapshot read-only, verificar constraints/órfãos conhecidos, sequences e Flyway; não afirmar preservação integral por contagens. Comparação integral antes/depois requer evidência/teste de integração ou mecanismo de digestão de dados aprovado, com proteção contra vazamento e avaliação de custo.
- **Arquivos previstos:** `DatabaseInventoryCommand.java`, `DatabaseInventoryCommandIntegrationTest.java`, `inventory-usage.md`; qualquer contrato de evidência externo permanece documental até autorização.
- **Dependências:** D1/D4 para identidade/classes; a prova final integra D7. Evidência de backup/consumidores/corte não é inferida do catálogo.
- **Testes PG16/Testcontainers:** contagens estáveis em snapshot; constraints válidas/inválidas; sequence state; auditoria append-only; Flyway; inserir/alterar dados entre snapshots para demonstrar que contagem isolada não prova igualdade; comparação antes/depois sem alteração pelo inventário.
- **Aceite:** relatório separa executável, integração e operador; contagem não é declarada checksum; evidências externas têm tipo, referência não secreta, emissor, timestamp, estado e aprovação, sem senha/token/URL secreta; ausência bloqueia autorização operacional.
- **Riscos:** custo de varredura, dados mudando durante janela, PII em digest/relatório, evidência externa desatualizada ou não autenticada.
- **Fora:** backup/restore, corte de tráfego, validação de consumers reais ou armazenamento de credenciais pelo inventário.

Contrato de evidência externa planejado: evidência de backup concluído e restauração testada (referência/escopo/data/resultado/operador); lista de consumidores/serviços revisada com versão e role utilizada; confirmação de distribuição protegida das credenciais e teste de autenticação sem revelar valor; janela de manutenção e controle de tráfego; confirmação de API antiga parada ou limitada a runtime; aprovação nominal do operador e evidência final de corte. Cada item é `NOT_PROVIDED`, `PROVIDED_UNVERIFIED`, `VERIFIED` ou `REJECTED`, com emissor, data e referência. O inventário apenas registra metadados não secretos fornecidos; não transforma declaração do operador em fato catalogal.

###### T-05.3-D7 — Consolidação, classificação e relatório

- **Objetivo/escopo:** integrar coletores D1–D6, esquema de relatório versionado, classificação fail-closed para os dois estados iniciais T-05.1 e, somente após autorização D-06/D-10, estados transientes reconhecidos; relatório explica evidência, lacunas e bloqueadores sem produzir mutação.
- **Arquivos previstos:** `DatabaseInventoryCommand.java`, `JavaModernizationPlatformApplication.java` apenas se integração da entrada mudar, `DatabaseInventoryCommandIntegrationTest.java`, `inventory-usage.md`.
- **Dependências:** D1–D6 concluídas e contratos D-06–D-10 explicitamente aprovados quando aplicáveis.
- **Testes PG16/Testcontainers:** matriz completa T-05.1; combinações de dados ausentes/role/membership/ACL/ownership/journal; `UNKNOWN` versus `REJECTED`; relatório determinístico e sem segredo; teste de somente leitura comparando catalogs/dados/roles/grants/history antes/depois; principal de leitura sem visibilidade suficiente.
- **Aceite:** cada capacidade obrigatória tem status/cobertura; nenhum `SUPPORTED_*` com campo obrigatório não verificado; estados iniciais não ampliados; código de saída/documentação coerentes; relatório não contém senha, verifier, token, URL com segredo ou valor de configuração sensível.
- **Riscos:** inconsistência de snapshot entre conexões Flyway/JDBC, tamanho do relatório, compatibilidade do schema JSON e falso positivo por catálogo não visível.
- **Fora:** iniciar transição, executar DDL/DML, ampliar matriz aprovada, tocar migrations ou credenciais.

###### Sequência e dependências

Sequência recomendada: **D1 → D2 e D4 (paralelizáveis após D1) → D3 (após D2+D4) → D5 (após D1–D4 e aprovação D-06/D-10) e D6 (pode evoluir em paralelo após D1/D4) → D7 (integra todos)**. D5 tem bloqueio de aprovação explícita mesmo se D1–D4 estiverem prontos. Cada tarefa futura requer autorização individual, plano de arquivos final e Testcontainers PostgreSQL 16. A execução de uma tarefa não aprova a seguinte.

| Lacuna T-05.3-C | Responsável planejada | Severidade | Condição para fechar |
|---|---|---|---|
| Atributos incompletos, provisionadora não consultada, ausência/status ambíguos e config sensível | D1 | Bloqueadora | Nove atributos e principals relevantes, status/origem por campo, redaction e classificação cobertos. |
| Memberships diretas apenas, sem caminho/opções efetivas | D2 | Bloqueadora | Grafo PG16 e paths SET/INHERIT/ADMIN verificados com fixtures. |
| Efetivos sem proveniência, PUBLIC/defaults/ownership incompletos | D3 | Bloqueadora | Efetivo cruzado com ACL/grafo e fonte explicada. |
| Database, classes, owner/ACL/dependências e OIDs parciais | D4 | Bloqueadora | Cobertura por classe e identidade/limitações; desconhecidos abortados. |
| Journal não reconhecido e sem reconciliação checkpoint/efeito | D5 | Bloqueadora | Só após aprovação D-06/D-10; cenários válidos/inválidos testados. |
| Contagens não provam preservação; backup/consumidores/corte externos | D6 | Importante para inventário, bloqueadora para aprovação operacional | Camadas de evidência separadas, comparação pós-etapa e prova externa rastreável. |
| Relatório/classificador atual não compõe as novas evidências | D7 | Bloqueadora | Matriz ponta a ponta falha fechado e read-only verificada. |

##### Estratégia comum de testes futuros e riscos

Todos os testes de catálogo/semântica usam PostgreSQL 16 real por Testcontainers com banco descartável, principals/setup separados de runtime/migrator/bootstrap/provisionadora e credenciais efêmeras. Fixture é montada antes da chamada de inventário; a chamada do inventário roda read-only; snapshots antes/depois comparam dados, roles, memberships, ownership, ACLs, extensões, history e journal. Testes de autenticação/`SET ROLE` são conexões fixture separadas; nunca inserir segredo nos asserts/relatórios. Casos de erro de privilégio de leitura devem resultar em não verificável, não em estado seguro. Testes de transição, se autorizados futuramente, são projeto/tarefa separado em banco descartável, nunca prova por teste de inventário.

Riscos transversais: privilégios de catálogo insuficientes podem ocultar objetos; crescimento/performance de graph e ACL queries; dados/DDL mudando após snapshot (TOCTOU); OIDs não são identidade global/perpétua; settings podem conter segredo; journal não resiste por si só a owner/superuser; checksum de dados pode vazar informação ou ser caro; estados de transição exigem aprovação e não podem ampliar silenciosamente T-05.1. Mitigar com status de cobertura, fingerprint, abortos, redaction, snapshots, fixture PG16 e revisão por subtarefa.

##### D-06 — Checkpoints e retomada

Alternativas: (a) arquivo externo como fonte autoritativa — simples de arquivar, mas não pode fazer commit atômico com PostgreSQL; (b) tabela journal somente no banco — permite atomicidade, mas exige uma exceção estrita no inventário; (c) checkpoint por etapa transacional, combinado com exportação externa — mantém o estado do banco como fonte de verdade e o arquivo como evidência/cópia operacional. **Recomendação: (c).**

Criar um schema reservado `platform_transition` e tabelas de journal/eventos somente dentro da primeira transação de alteração, com uma linha que identifique UUID da transição, identificador do destino, hash SHA-256 do relatório de inventário aprovado, versão do procedimento e estado atual. A cada etapa, alteração PostgreSQL e evento/checkpoint são gravados no mesmo commit; assim, queda antes do commit deixa ambos ausentes e queda depois deixa ambos persistidos. O arquivo externo registra eventos observados, mas nunca autoriza retomada sozinho. O inventário deverá, em trabalho futuro e aprovado, retornar `TRANSITION_IN_PROGRESS` apenas se schema, tabelas, colunas, constraints, UUID, destino, versão e sequência de eventos corresponderem exatamente ao formato conhecido; após conclusão, reconhecer `TRANSITION_COMPLETE`. Esses são estados operacionais, não ampliação dos estados iniciais aceitos pela T-05.1; qualquer journal desconhecido continua sendo recusado. Reter o journal completo como evidência protegida e fonte de idempotência; não apagá-lo automaticamente. Se houver queda após o evento final e antes do relatório externo, a reexecução pode validar o estado e emitir `ALREADY_COMPLETE`; sem equivalência exata, aborta.

Destino = identidade do servidor/endpoint fornecida sem segredo pelo operador + nome e OID do database. O comando também exige UUID novo de transição, versão fixa do procedimento, hash do relatório aprovado e confirmação literal vinculada ao identificador do destino e ao hash. Um advisory lock por destino impede duas transições concorrentes; o lock não substitui o journal nem a confirmação. Estados propostos: `STARTED`, `ROLES_CREATED_NOLOGIN`, `CREDENTIALS_PROVISIONED`, `ROLES_READY`, `CREDENTIALS_VALIDATED`, `EXTENSION_VERIFIED`, `OWNERSHIP_IN_PROGRESS`, `OWNERSHIP_DONE`, `V5_RECONCILED`, `GRANTS_DONE`, `FINAL_VERIFIED` e `COMPLETE`. Para ownership, registrar por objeto classe/OID, identidade qualificada e owner anterior/esperado; aplicar e registrar cada transferência na mesma transação curta. As demais etapas são transacionais como unidade sempre que possível.

Contrato lógico mínimo do journal: UUID `transition_id`; fingerprint do destino (endpoint lógico não secreto, database OID/nome e versão do servidor); versão do contrato; estado Flyway inicial (`V1_V4` ou `V1_V5`); SHA-256 do JSON de inventário aprovado; número monotônico do evento; etapa; identidade nominal da execução, `session_user` e `current_user`; timestamp UTC do servidor; resultado/código não sensível; referência do objeto (classe/OID e identidade qualificada); owner/grant anterior e esperado; SHA-256 do evento anterior e atual. Nunca armazenar credenciais, hashes de senha, linhas de dados ou URL com parâmetros secretos. Eventos append-only, únicos e sequenciais por transição; estado atual derivado do último evento válido. No máximo uma transição ativa por fingerprint.

ACL do journal sem acesso de `PUBLIC`, `app_runtime` ou `app_bootstrap`; leitura e append somente para identidade de transição; consumidores sem UPDATE/DELETE/TRUNCATE. Hash encadeado detecta corrupção acidental, não é assinatura contra owner do journal ou superuser. O contrato confia no operador/DBA autorizado. Se for necessário resistir à adulteração por essas identidades, precisa-se de mecanismo independente de assinatura, fora da recomendação atual. A cópia externa contém UUID, fingerprint e hashes e é apenas evidência; nunca sobrepõe o PostgreSQL.

O inventário poderá reconhecer `TRANSITION_IN_PROGRESS`/`TRANSITION_COMPLETE` somente para exatamente um journal na versão conhecida, cadeia válida e estado efetivo idêntico ao evento/prefixo. Isso é estado operacional, não ampliação dos estados iniciais T-05.1. Journal ausente com mutação, duplicado, incompleto, cadeia/versão/hash/destino divergente, tabela/coluna extra ou efeito sem evento resulta em `UNKNOWN/REJECTED` e intervenção manual. Journal sozinho nunca prova conclusão.

Fronteiras sem transação compartilhada: Flyway V5 executa separadamente como `app_migrator`; após queda antes do evento, só reconciliar com `success=true`, checksum versionado, estado Flyway esperado e definição/estrutura pós-V5 exatas. Qualquer parcial aborta; nunca chamar `repair`. Credenciais são provisionadas/testadas em conexão separada; se a validação falhar, não redefinir senha. Cópia externa ausente após commit é reconstruída do journal validado, sem repetir mutação.

Reconciliação: comparar journal, fingerprint/destino, inventário atual e estado efetivo de cada objeto/grant. Reexecutar apenas etapa sem efeito cujo checkpoint não existe, ou reconhecer efeito e checkpoint juntos. Um efeito sem evento correspondente, evento sem efeito, mudança externa de objeto, hash/destino/versão diferente, sequência que não seja prefixo válido ou journal não reconhecido exige aborto e diagnóstico manual. Não usar checkpoint isolado como prova de conclusão; a última etapa sempre refaz validações de catálogo, dados, integridade, Flyway e grants. Não prometer rollback global: commits anteriores permanecem; recuperação é forward-only ou restore operacional aprovado.

##### D-07 — Identidade provisionadora

Alternativas: credencial administrativa compartilhada existente (pode ter privilégio excessivo e identidade pouco rastreável); role dedicada de operação (separação e auditoria melhores); identidade temporária (limita duração, mas precisa de ciclo de criação/revogação confiável). **Recomendação: role dedicada por ambiente, ativada apenas para a janela, não usada por API, Flyway, runtime ou bootstrap; se a política local não permitir uma role dedicada permanente, identidade temporária provisionada por DBA e removida após a operação.** Não presumir capacidades por nome da role.

Pré-condições verificáveis: PostgreSQL 16; `LOGIN`; `NOSUPERUSER`, `NOCREATEDB`, `NOREPLICATION`, `NOBYPASSRLS`; `CREATEROLE` apenas no fluxo V1–V4 que realmente cria roles; `CONNECT`; capacidade comprovada de administrar memberships necessárias; `SET` permitido para o owner legado e para `app_migrator`; owner corrente (ou membership que dê autoridade de owner) sobre cada objeto a transferir; e permissão de criar/remover journal schema. Para V1–V5, não requerer nem usar `CREATEROLE`. O inventário atual não inclui `rolbypassrls`; sua futura extensão é pré-requisito para validar este atributo. Rejeitar qualquer caminho que dependa de superuser, `REASSIGN OWNED`, mudança de senha de role existente ou grant implícito. O estado de membership e `SET ROLE` deve ser consultado antes e revalidado durante a execução. `CREATEROLE` no PostgreSQL 16 é uma capacidade ampla: operador temporário deve ter acesso protegido, sessão controlada, credencial rotacionada/revogada ao fim e plano de revogação em falha; não deve receber membership em roles da aplicação. O protocolo exato de memberships e revogação exige prova em fixture antes da implementação.

| Etapa | Capacidades requeridas | Memberships/escopo | Aborto objetivo |
|---|---|---|---|
| Perfil em todas as execuções | PostgreSQL 16; `LOGIN`; `NOSUPERUSER`, `NOCREATEDB`, `NOREPLICATION`, `NOBYPASSRLS`, `NOINHERIT`; `CONNECT`; validade/expiração explícita; `rolconfig` vazio ou allowlist exata. | Sem membership em `app_runtime`/`app_bootstrap`; sem ADMIN OPTION não aprovado. Verificar a cadeia e opções `SET`, `INHERIT`, `ADMIN` de cada membership. | Qualquer atributo, configuração ou privilégio excessivo/desconhecido. O inventário atual omite `rolbypassrls`, `rolvaliduntil` e `rolconfig`; futura extensão é pré-requisito e não faz parte desta tarefa. |
| Ler/inventariar | SELECT de catálogos, histórico, owners, ACLs, dependências e contagens. | Nenhum `SET ROLE` requerido para classificar. | Dado necessário sem visibilidade. |
| Criar journal | CREATE schema no database (não `CREATEDB`); SELECT/INSERT na tabela journal. | Journal sem grants a runtime/bootstrap. | Não criar com ACL restrita ou houver acesso consumidor. |
| Criar roles (somente V1–V4 sem roles preexistentes) | `CREATEROLE`; não superuser. | Memberships temporárias explícitas para owner legado e `app_migrator`, apenas com opções necessárias; nenhuma para runtime/bootstrap. Remover membership temporária no final. | Qualquer role alvo preexistente; privilégio amplo não limitado; membership inesperada. |
| V1–V5 | Validar as três roles existentes sem `CREATEROLE`. | Membership `SET` para legacy owner/app_migrator deve estar pré-autorizada e registrada; sem runtime/bootstrap. | Atributo ou membership divergente. |
| Ownership/grants | Autoridade de owner sobre origem; `SET ROLE` efetivo para `app_migrator`; autoridade de GRANT/REVOKE nas ACLs aprovadas. | Alterar somente OIDs/identidades listados no inventário com o fingerprint aprovado. | Não provar owner e `SET` ao novo owner, objeto desconhecido ou ACL sem origem classificada. |
| Extensão/verificação | SELECT de catálogo e teste de função sem alterar extensão; conexão protegida como consumers para validação. | Owner `pgcrypto` preservado; nenhum `CREATE EXTENSION`. | Extensão ausente/divergente ou consumer não validável. |

O inventário da provisionadora deve coletar e validar `rolsuper`, `rolcreaterole`, `rolcreatedb`, `rolreplication`, `rolbypassrls`, `rolinherit`, `rolcanlogin`, `rolvaliduntil`, `rolconfig` e memberships com opções. Para executor temporário, `rolvaliduntil` finito não basta: a limitação de expiração pode depender do método de autenticação; encerrar exige revogar memberships temporárias e desabilitar LOGIN pelo DBA/identidade autorizada. A prova de capacidade de transferir objetos deve combinar owner atual (ou membership de owner válida) e `SET` para o novo owner. A documentação oficial PostgreSQL 16 especifica esses requisitos de membership/ownership; a capability efetiva ainda depende de prova em fixture.

##### D-08 — Credenciais e roles

| Estado encontrado | Recomendação proposta | Resultado |
|---|---|---|
| V1–V4 sem roles alvo | Criar as três roles em estado NOLOGIN e sem senha, na transação com checkpoint. Em etapa separada, definir credenciais novas por canal protegido aprovado e habilitar LOGIN com evento próprio; testar conexão como cada role sem expor valores. | Prosseguir somente após conexão validada, distribuição segura e nenhum consumidor privilegiado na API. |
| V1–V4 com uma ou mais roles alvo | Não completar, alterar nem apagar role existente automaticamente. | Recusar; permanece fora da T-05.1. |
| V1–V5 com as três roles existentes e atributos/memberships exatos | Não alterar senhas nem atributos; usar identidade provisionadora independente. | Prosseguir somente se credenciais necessárias aos consumidores puderem ser validadas por conexões protegidas antes do corte. |
| Role existente com senha desconhecida | Senha não é legível de modo seguro pelo procedimento; não tentar extrair hash, redefinir ou registrar. | Se a role for V1–V5 necessária ao rollout, abortar antes de mutação até que o operador valide/acione rotação explícita externa. |
| Atributos, memberships ou grants divergentes; role sem LOGIN | Não normalizar automaticamente nem conceder LOGIN. | Recusar para diagnóstico manual, mesmo que o operador conheça a senha. |
| Credencial existente conhecida e validada | Usar apenas para teste de conexão pelo canal protegido; não incluir valor ou hash em inventário, checkpoint ou saída. | Seguir sem redefinir credencial. |

Rotação é operação separada, explicitamente autorizada e fora do procedimento de transição; requer plano de consumidores e distribuição antes do corte. Em hipótese alguma testar credenciais inserindo valores em SQL de log ou saída.

Canal proposto: prompt interativo sem eco em cliente PostgreSQL separado, ou canal corporativo de entrega de segredo já aprovado com TLS e ACLs; nunca argumento, environment dump, shell history, SQL em arquivo, log, relatório, journal ou temporário desprotegido. É proibido enviar `CREATE ROLE ... PASSWORD 'cleartext'`: PostgreSQL alerta que a senha pode trafegar em claro e ser registrada. O fluxo V1–V4 cria roles `NOLOGIN` sem senha na etapa transacional; senha é definida fora dela via `psql \\password` (ou canal corporativo aprovado), sem colocar segredo em argumento/script. O operador testa conexão por caminho autenticado por senha sobre TLS e só então registra evento sem valor/hash. `psql \\password` evita exposição da senha em claro segundo a documentação, mas o ambiente deve ainda verificar política de logging/auditoria e se verifier/hash poderia aparecer. Se isso não puder ser verificado, abortar antes da etapa. Buffers devem ser minimizados e limpos best-effort, cliente encerrado e nenhum buffer/arquivo temporário mantido; não alegar zeroização garantida de memória Java/OS. Credencial já existente nunca é redefinida para recuperar falha; o relatório/journal só recebe resultado booleano/código da validação.

Como a definição de credenciais ocorre fora da transação PostgreSQL que grava o journal, não há atomicidade entre a senha e o checkpoint. Após a definição, a retomada exige que o operador reapresente a credencial pelo canal protegido e valide a autenticação antes de gravar `CREDENTIALS_PROVISIONED`/`CREDENTIALS_VALIDATED`; queda ou falha não autoriza redefinição automática. Se não for possível provar a autenticação e a distribuição da credencial, interromper e exigir rotação externa explicitamente aprovada, seguida de novo inventário/validação.

##### D-09 — Matriz proposta de ownership e privilégios

| Classe | Owner atual / condição inicial | Owner final | Executor | Grants necessários/proibidos | ACL removível | Aborto |
|---|---|---|---|---|---|---|
| Database | Owner exato do inventário; pode diferir do owner legado. | Preservar. | Nenhum takeover. CREATE temporário só para schema journal. | Consumers: CONNECT; runtime/bootstrap sem CREATE/TEMP/CREATEDB. | Somente ACL não prevista, origem conhecida e database inventariado. | Owner/ACL desconhecidos ou exigir takeover do database. |
| Schema `public` | Owner e ACL do inventário. | Preservar. | Nenhuma alteração de owner. | Migrator USAGE, CREATE; runtime/bootstrap USAGE, sem CREATE; PUBLIC sem CREATE. | CREATE de PUBLIC e grants extras somente em schema inventariado. | ACL desconhecida ou transferência de schema owner necessária. |
| Journal `platform_transition` | Ausente inicialmente; apenas formato/versionamento definidos. | Owner `app_migrator` ou owner técnico definido antes da implementação. | Provisionador cria; executor lê/anexa. | Sem acesso a runtime/bootstrap/PUBLIC; consumidores sem UPDATE/DELETE/TRUNCATE. | Nunca limpar journal divergente; manter completo como evidência. | Schema/journal pré-existente, duplicado ou ACL/owner divergente. |
| Tabelas `users`, `customers`, `products`, `orders`, `order_items` | Owner legado único e ACLs exatas por OID/identidade. | `app_migrator`. | Provisionador com autoridade sobre owner antigo e SET para `app_migrator`. | Runtime SELECT/INSERT/UPDATE, sem DELETE/TRUNCATE/DDL. Bootstrap SELECT/INSERT só em users. | ACL residual do legado/PUBLIC/roles extras, objeto a objeto, após allowlist. | Owner/ACL/objeto extra ou grant de origem desconhecida. |
| `user_customer_link_audit` | Owner legado único, estrutura/trigger V4 íntegros. | `app_migrator`. | Igual à tabela de negócio. | Runtime SELECT/INSERT apenas; bootstrap sem grants; sem UPDATE/DELETE/TRUNCATE. | ACL não prevista somente no objeto inventariado. | Auditoria/trigger divergente ou integridade não comprovada. |
| Índices e constraints | Owner segue a relação/tabela; definição e validação inventariadas. | Acompanham a tabela sob `app_migrator`. | Transferência da tabela; sem ALTER OWNER independente. | Sem grants específicos a consumer. | Só ACL catalogada. | Extra, inválido, não validado ou owner incoerente. |
| Sequences | Nenhuma prevista em V1–V5. | Não aplicável. | Nenhum. | Nenhum. | Nenhuma remoção automática. | Qualquer sequence fora da matriz. |
| Views/materialized views/procedures | Nenhuma prevista. | Não aplicável. | Nenhum. | Nenhum. | Nenhuma. | Qualquer uma encontrada. |
| Funções V4/V5 | Source/assinatura/owner legado/ACL exatos; `SECURITY INVOKER`. ACL inicial esperada conforme cada migration. | `app_migrator`. | Owner legado/membership válida transfere só funções listadas. | V4 mantém ACL `PUBLIC EXECUTE` se corresponder ao default da migration; V5 mantém `REVOKE ALL ... FROM PUBLIC`. Migrator precisa EXECUTE para criar trigger. | Somente ACLs não previstas para a função; não normalizar V4 ao estado V5 sem decisão aprovada. | `SECURITY DEFINER`, definição divergente ou ACL extra desconhecida. |
| Triggers V4/V5 | Anexados às tabelas; não têm owner independente. | Acompanham tabela app_migrator. | Indiretamente via tabela. | Runtime/bootstrap sem TRIGGER/DDL; enabled e definição como migrations. | Não há ACL separada. | Trigger extra/desativado/divergente. |
| Types/domains/enums | Nenhum tipo independente previsto; row type implícito acompanha tabela. | Row type segue tabela; demais não aplicável. | Pela tabela se suportado. | Nenhum grant extra. | Nenhuma limpeza ampla. | Domain/enum/type independente extra. |
| `pgcrypto` e objetos membros | Owner/schema/versão atuais, como inventário; não assumir nome `provisioner`. | Preservar integralmente. | Nenhuma transferência ou CREATE EXTENSION. | Só EXECUTE exigido pelo UUID default, comprovado; sem CREATE EXTENSION por consumers/migrator. | Não remover ACL de objetos membros nesta subtarefa. | Extensão/owner/schema/versão/função divergente. |
| Flyway history e internos PostgreSQL | Owner/ACL atuais classificados; catálogos internos nunca transferir. | Preservar. V5 somente pelo job Flyway quando origem V1–V4. | `app_migrator` para Flyway V5. | Runtime/bootstrap sem DML no history; sem manipular history manualmente. | Nenhuma limpeza genérica. | Migration/history divergente ou objeto interno tocado. |

No PostgreSQL, triggers e índices/constraints pertencentes a tabela não recebem transferência independente como uma tabela. As funções atuais V4/V5 são PL/pgSQL sem `SECURITY DEFINER`; a transição deve abortar se `prosecdef`/definição diferir. Não se deve adicionar `search_path` ou alterar corpo nessa etapa: se uma futura função SECURITY DEFINER for proposta, exigirá análise separada de `search_path` fixo, schema qualificado e EXECUTE de PUBLIC. V4 cria a função de auditoria sem revogar EXECUTE de PUBLIC; V5 revoga EXECUTE público da função ADMIN. Preservar essa diferença enquanto não houver análise/autorização para alterá-la. PostgreSQL exige EXECUTE ao criar trigger; testes futuros devem provar que os triggers existentes seguem operando com a ACL proposta, além de tentar chamar as funções e alterar/desabilitar triggers com runtime/bootstrap. Para cada objeto da aplicação, a ACL final deve corresponder integralmente à allowlist da matriz; remover grants residuais do owner legado e roles não previstas somente nos objetos inventariados, sem alterar atributos LOGIN da role legada em nível de cluster. PUBLIC só permanece quando fizer parte da ACL esperada pela migration. Se a origem do grant não puder ser classificada, abortar.

Referências primárias para validar as regras PostgreSQL 16 propostas: [role membership e opções SET/INHERIT/ADMIN](https://www.postgresql.org/docs/16/role-membership.html), [CREATE ROLE e CREATEROLE](https://www.postgresql.org/docs/16/sql-createrole.html), [visão `pg_roles`](https://www.postgresql.org/docs/16/view-pg-roles.html), [ALTER ROLE e atributos](https://www.postgresql.org/docs/16/sql-alterrole.html), [psql `\\password`](https://www.postgresql.org/docs/16/app-psql.html), [ownership e privilégios de objetos](https://www.postgresql.org/docs/16/ddl-priv.html) e [privilégio EXECUTE para CREATE TRIGGER](https://www.postgresql.org/docs/16/sql-createtrigger.html). A semântica efetiva ainda deve ser provada com as identidades de fixture antes da implementação.

##### D-10 — Estados parciais e recuperação

Máquina de estados proposta, sem equivalência com autorização de implementação: estado inicial aprovado `V1_V4` (sem roles-alvo) ou `V1_V5` (roles/atributos exatos) → `STARTED` com journal criado → `ROLES_CREATED_NOLOGIN` (somente V1–V4) → provisionamento externo e evento `CREDENTIALS_PROVISIONED` → `ROLES_READY` (LOGIN/atributos/memberships verificados) → `CREDENTIALS_VALIDATED` → `EXTENSION_VERIFIED` → `OWNERSHIP_IN_PROGRESS` com prefixo de OIDs registrado por commits → `OWNERSHIP_DONE` → V1–V4 executa Flyway V5 e só então grava `V5_RECONCILED`; V1–V5 valida V5 existente e grava reconciliação → `GRANTS_DONE` → `FINAL_VERIFIED` → `COMPLETE`. No caminho V1–V5, `ROLES_CREATED_NOLOGIN` e o provisionamento de credenciais são pulados, sem alterar credenciais ou atributos das roles existentes. O `STARTED` precede a primeira mutação de roles; se a transação que cria o journal/roles falhar, a ausência de qualquer efeito e journal deve ser comprovada contra o inventário inicial antes de nova execução.

Cada etapa transacional inclui seu evento no mesmo commit; grants são aplicados como unidade. Transferências de ownership são commits curtos por OID, com evento contendo o objeto e owner anterior/esperado. Flyway V5 é fronteira externa: após crash entre commit Flyway e evento, aceitar reconciliação apenas quando `success`, checksum versionado, histórico e estrutura V5 coincidirem exatamente; não executar `repair` nem reaplicar DDL manual. A credencial permanece fora do journal; após crash na provisionagem externa, revalidar credencial reapresentada e acrescentar somente resultado não sensível. O journal `COMPLETE` é retido e deve ser comparado ao estado efetivo para responder `ALREADY_COMPLETE` sem mutação.

| Estado encontrado | Detecção / retomada | Ação permitida | Aborto |
|---|---|---|---|
| Falha antes do primeiro commit/journal | Não há journal e estado inicial permanece exatamente igual ao relatório aprovado. | Reiniciar com nova confirmação vinculada ao mesmo fingerprint ainda válido. | Se houve qualquer mudança sem journal. |
| V1–V4: criação de roles abortada antes do commit | O evento `STARTED` permanece; a transação da etapa desfaz todas as roles/eventos não commitados. | Reexecutar somente se catálogo ainda mostra zero roles-alvo e fingerprint/journal correspondem. | Qualquer role parcialmente criada, atributo inesperado ou efeito sem checkpoint. |
| Queda após commit `ROLES_READY` | Journal e conjunto exato de roles novos devem existir; credenciais nunca são relidas do journal. | Revalidar atributos/memberships, testar conexões via canal protegido e prosseguir. | Role ausente, atributo/membership/credencial não validável ou operador alterado. |
| Queda durante provisionamento externo de credencial | Nenhuma senha/verifier no journal; testar autenticação pela credencial reapresentada via canal protegido. | Se conexão passa, anexar `CREDENTIALS_VALIDATED` sem valor/hash e seguir. | Se falha ou logging/canal não é verificável, abortar; não redefinir automaticamente. |
| Falha na validação de extensão | Journal registra etapa, sem alteração de ownership/grants; `pgcrypto` deve continuar com owner/schema/versão originais. | Corrigir externamente o pré-requisito e iniciar nova aprovação/inventário se isso mudar estado. | Extensão ausente/divergente ou tentativa de criá-la como migrator/runtime. |
| Transferência parcial de ownership | Comparar cada OID/identidade e owner com o evento correspondente do journal. | Transferir apenas próximo objeto esperado se cada anterior confere com evento e cada posterior ainda tem owner legado. | Owner sem evento, evento sem objeto, objeto renomeado/recriado/extra ou ordem não prefixo. |
| Falha durante aplicação de grants | Aplicar toda matriz de grants numa transação e gravar `GRANTS_DONE` no mesmo commit. | Se rollback comprovado, repetir a etapa; se commit presente, verificar estado e avançar. | Grant não previsto, ACL divergente, privilege herdado inesperado ou checkpoint inconsistente. |
| Queda entre etapa e checkpoint | Impossível dentro de etapa projetada: alteração e evento têm mesmo commit. | Reconciliar commit efetivo pelo journal e catálogos. | Se operação foi executada fora da transação/procedimento ou não há prova atômica. |
| Commit ocorrido, cópia externa do checkpoint ausente | Ler journal PostgreSQL, validar hash/destino/versão e reconciliar arquivo como cópia incompleta. | Continuar somente com estado efetivo exatamente igual ao checkpoint. | Qualquer mismatch entre journal e objetos. |
| Reexecução concluída | Journal `COMPLETE` retido; comparar cadeia, destino, Flyway, objetos, dados, roles, extensões, memberships e grants finais. | Retornar `ALREADY_COMPLETE`, sem DDL/DML, somente se equivalência integral for provada. | Journal faltante/duplicado ou mudança posterior não explicada. |
| Estado parcial desconhecido ou journal alterado | UUID, sequência, eventos, checksums, objeto/destino/versão não reconhecidos ou alterações não descritas. | Nenhuma mutação automática; diagnóstico e plano novo aprovados. | Sempre. Não tentar reparar, limpar journal ou voltar owners automaticamente. |

Recuperação forward-only: manter API fora do corte até sucesso final; preservar journal e logs em falha; não retornar à credencial antiga privilegiada como fallback. Restaurar backup é decisão operacional externa, avaliada por perda de transações, nunca rollback automático do procedimento.

Máquina de estados ordenada: `INITIAL_V1_V4` ou `INITIAL_V1_V5` → `STARTED` → `ROLES_READY` → `CREDENTIALS_VALIDATED` → `EXTENSION_VERIFIED` → `OWNERSHIP_IN_PROGRESS(0..N)` → `OWNERSHIP_DONE` → (`V5_RECONCILED`, somente para V1–V4) → `GRANTS_DONE` → `FINAL_VERIFIED` → `COMPLETE`. Em V1–V5, V5 já íntegra é pré-condição. Toda retomada demonstra que eventos válidos formam exatamente um prefixo desta sequência e cada OID, owner, role, membership e grant efetivo coincide com o prefixo. Qualquer mudança externa, evento sem efeito, efeito sem evento ou salto de etapa bloqueia retomada automática. O estado final exige dados/constraints, ownership, ACLs, extensão, proteções, Flyway e conexões consumidoras validados; journal completo fica retido, sem segredo.

##### Contrato operacional recomendado

Exigir inventário recente e hash fixo; relatório sem mudanças entre inventário e início; identidade inequívoca de servidor/database; evidência operacional externa de backup restaurado em exercício; janela aprovada; consumidores privilegiados controlados; credentials via canal protegido; confirmação literal que contenha UUID do destino e hash do inventário; e verificação pós-transição completa antes de iniciar API com `app_runtime`. Simulação mantém conexão `READ ONLY`, não cria journal nem advisory lock persistente e apenas apresenta etapas/bloqueadores. Classificação positiva nunca inicia execução automaticamente.

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
