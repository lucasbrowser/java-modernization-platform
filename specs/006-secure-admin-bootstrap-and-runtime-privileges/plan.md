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

#### T-05.3-A — Propostas de contrato D-06 a D-10 (aguardando aprovação)

As opções abaixo são recomendações técnicas decorrentes da inspeção de V1–V5, `provision-postgres-roles.sql`, T-03 e do inventário T-05.2. Não são decisões aprovadas. T-05.3 permanece incompleta até aprovação explícita e validação posterior em PostgreSQL 16 descartável.

##### D-06 — Checkpoints e retomada

Alternativas: (a) arquivo externo como fonte autoritativa — simples de arquivar, mas não pode fazer commit atômico com PostgreSQL; (b) tabela journal somente no banco — permite atomicidade, mas exige uma exceção estrita no inventário; (c) checkpoint por etapa transacional, combinado com exportação externa — mantém o estado do banco como fonte de verdade e o arquivo como evidência/cópia operacional. **Recomendação: (c).**

Criar um schema reservado `platform_transition` e tabelas de journal/eventos somente dentro da primeira transação de alteração, com uma linha que identifique UUID da transição, identificador do destino, hash SHA-256 do relatório de inventário aprovado, versão do procedimento e estado atual. A cada etapa, alteração PostgreSQL e evento/checkpoint são gravados no mesmo commit; assim, queda antes do commit deixa ambos ausentes e queda depois deixa ambos persistidos. O arquivo externo registra eventos observados, mas nunca autoriza retomada sozinho. O inventário deverá, em trabalho futuro e aprovado, retornar `TRANSITION_IN_PROGRESS` apenas se schema, tabelas, colunas, constraints, UUID, destino, versão e sequência de eventos corresponderem exatamente ao formato conhecido. Isso é estado operacional intermediário, não ampliação dos estados iniciais aceitos pela T-05.1; qualquer journal desconhecido continua sendo recusado. Ao concluir, a verificação final e remoção transacional do journal/schema ocorrem no mesmo commit. Se houver queda depois desse commit e antes do relatório externo final, a reexecução pode reconhecer o estado final por comparação integral com o alvo e emitir `ALREADY_COMPLETE`; sem equivalência exata, aborta.

Destino = identidade do servidor/endpoint fornecida sem segredo pelo operador + nome e OID do database. O comando também exige UUID novo de transição, versão fixa do procedimento, hash do relatório aprovado e confirmação literal vinculada ao identificador do destino e ao hash. Um advisory lock por destino impede duas transições concorrentes; o lock não substitui o journal nem a confirmação. Estados propostos: `STARTED`, `ROLES_READY`, `EXTENSION_VERIFIED`, `OWNERSHIP_IN_PROGRESS`, `OWNERSHIP_DONE`, `GRANTS_DONE`, `FINAL_VERIFIED` e `COMPLETE`. Para ownership, registrar por objeto classe/OID, identidade qualificada e owner anterior/esperado; aplicar e registrar cada transferência na mesma transação curta. As demais etapas são transacionais como unidade sempre que possível.

Reconciliação: comparar journal, fingerprint/destino, inventário atual e estado efetivo de cada objeto/grant. Reexecutar apenas etapa sem efeito cujo checkpoint não existe, ou reconhecer efeito e checkpoint juntos. Um efeito sem evento correspondente, evento sem efeito, mudança externa de objeto, hash/destino/versão diferente, sequência que não seja prefixo válido ou journal não reconhecido exige aborto e diagnóstico manual. Não usar checkpoint isolado como prova de conclusão; a última etapa sempre refaz validações de catálogo, dados, integridade, Flyway e grants. Não prometer rollback global: commits anteriores permanecem; recuperação é forward-only ou restore operacional aprovado.

##### D-07 — Identidade provisionadora

Alternativas: credencial administrativa compartilhada existente (pode ter privilégio excessivo e identidade pouco rastreável); role dedicada de operação (separação e auditoria melhores); identidade temporária (limita duração, mas precisa de ciclo de criação/revogação confiável). **Recomendação: role dedicada por ambiente, ativada apenas para a janela, não usada por API, Flyway, runtime ou bootstrap; se a política local não permitir uma role dedicada permanente, identidade temporária provisionada por DBA e removida após a operação.** Não presumir capacidades por nome da role.

Pré-condições verificáveis: PostgreSQL 16; `LOGIN`; `NOSUPERUSER`, `NOCREATEDB`, `NOREPLICATION`, `NOBYPASSRLS`; `CREATEROLE` apenas no fluxo V1–V4 que realmente cria roles; `CONNECT`; capacidade comprovada de administrar memberships necessárias; `SET` permitido para o owner legado e para `app_migrator`; owner corrente (ou membership que dê autoridade de owner) sobre cada objeto a transferir; e permissão de criar/remover journal schema. Para V1–V5, não requerer nem usar `CREATEROLE`. O inventário atual não inclui `rolbypassrls`; sua futura extensão é pré-requisito para validar este atributo. Rejeitar qualquer caminho que dependa de superuser, `REASSIGN OWNED`, mudança de senha de role existente ou grant implícito. O estado de membership e `SET ROLE` deve ser consultado antes e revalidado durante a execução. `CREATEROLE` no PostgreSQL 16 é uma capacidade ampla: operador temporário deve ter acesso protegido, sessão controlada, credencial rotacionada/revogada ao fim e plano de revogação em falha; não deve receber membership em roles da aplicação. O protocolo exato de memberships e revogação exige prova em fixture antes da implementação.

##### D-08 — Credenciais e roles

| Estado encontrado | Recomendação proposta | Resultado |
|---|---|---|
| V1–V4 sem roles alvo | Criar as três roles aprovadas em transação, com credenciais novas fornecidas por canal protegido e separadas por consumidor; não gerar/mostrar senha no relatório, args ou log. Entrega ao operador ocorre fora do journal. | Prosseguir somente após conexão de validação protegida e confirmação de distribuição segura. |
| V1–V4 com uma ou mais roles alvo | Não completar, alterar nem apagar role existente automaticamente. | Recusar; permanece fora da T-05.1. |
| V1–V5 com as três roles existentes e atributos/memberships exatos | Não alterar senhas nem atributos; usar identidade provisionadora independente. | Prosseguir somente se credenciais necessárias aos consumidores puderem ser validadas por conexões protegidas antes do corte. |
| Role existente com senha desconhecida | Senha não é legível de modo seguro pelo procedimento; não tentar extrair hash, redefinir ou registrar. | Se a role for V1–V5 necessária ao rollout, abortar antes de mutação até que o operador valide/acione rotação explícita externa. |
| Atributos, memberships ou grants divergentes; role sem LOGIN | Não normalizar automaticamente nem conceder LOGIN. | Recusar para diagnóstico manual, mesmo que o operador conheça a senha. |
| Credencial existente conhecida e validada | Usar apenas para teste de conexão pelo canal protegido; não incluir valor ou hash em inventário, checkpoint ou saída. | Seguir sem redefinir credencial. |

Rotação é operação separada, explicitamente autorizada e fora do procedimento de transição; requer plano de consumidores e distribuição antes do corte. Em hipótese alguma testar credenciais inserindo valores em SQL de log ou saída.

##### D-09 — Matriz proposta de ownership e privilégios

| Categoria observada nas migrations | Owner/ação propostos | `app_runtime` | `app_bootstrap` | `app_migrator` / preservação |
|---|---|---|---|---|
| Schema `public` | Preservar owner atual classificado; revogar `CREATE` de `PUBLIC`; conceder `USAGE, CREATE` ao migrator como no provisionamento T-03. Não alterar schema owner automaticamente nesta faixa. | `USAGE`; sem `CREATE`. | `USAGE`; sem `CREATE`. | `USAGE, CREATE`; executa migrations. |
| Tabelas de negócio (`users`, `customers`, `products`, `orders`, `order_items`) | `app_migrator`; transferência explícita, objeto a objeto. | `SELECT, INSERT, UPDATE` como V5/T-03; sem `DELETE`, `TRUNCATE` ou DDL. Proteção V5 impede escrita ADMIN. | `SELECT, INSERT` apenas em `users`, como V5. | Owner e DDL de migration; sem `CREATEROLE`, `CREATEDB` ou superuser. |
| Tabela `user_customer_link_audit` | `app_migrator`. | `SELECT, INSERT`, sem UPDATE/DELETE/TRUNCATE. | Nenhum grant nesta tabela. | Owner para manutenção por migration. |
| Índices, constraints e row types de tabelas | Transferem-se com a tabela; não têm owner independente para este fim. | Sem grant direto separado. | Nenhum. | Owner acompanha a tabela. |
| Sequências | Nenhuma prevista nas migrations (UUIDs usam `pgcrypto`). Qualquer sequence extra aborta. Se uma migration futura introduzir sequência, definir grant mínimo por consumidor em subtarefa aprovada. | Nenhum na matriz atual. | Nenhum. | Owner se prevista por migration futura. |
| Views/materialized views e procedures | Nenhuma prevista; objetos encontrados são extras e abortam. | Nenhum. | Nenhum. | Nenhum na faixa atual. |
| Funções V4/V5 de trigger | `app_migrator`; preservar source; validar `prosecdef=false` (SECURITY INVOKER), assinatura, ACL e trigger. Proposta: revogar EXECUTE de PUBLIC e roles consumidoras; trigger já criado não deve ser enfraquecido. Não reescrever a função nem adicionar SECURITY DEFINER. | Sem EXECUTE direto. | Sem EXECUTE direto. | Owner; execute administrativo somente por necessidade verificada. |
| Triggers V4/V5 | Vinculados à tabela; seu ownership acompanha a tabela. Não são transferidos separadamente. | Não pode alterar/desabilitar. | Não pode alterar/desabilitar. | Controla via ownership das tabelas durante migrations; validar enabled e definição. |
| Tipos/domain/enums definidos pela aplicação | Nenhum enum/domain previsto; tipos compostos implícitos acompanham tabela. Tipo adicional independente aborta. | Nenhum grant extra. | Nenhum. | Transferir apenas se uma migration versionada futura o exigir. |
| Extensão `pgcrypto` e objetos membros | Preservar owner, versão, schema e objetos da extensão; não transferir propriedade em massa. `provisioner` ou owner atual classificado permanece owner. Verificar `gen_random_uuid()` disponível às expressões usadas. | Apenas EXECUTE necessário a defaults, confirmado em PostgreSQL 16; sem CREATE EXTENSION. | Idem somente se default for usado no fluxo bootstrap. | Sem CREATE EXTENSION; não transferir extensão. |
| Flyway history e objetos PostgreSQL internos | Preservar history/checksum/dados; objetos internos e catálogos nunca alterar. | Acesso estritamente necessário de leitura/DML via grants existentes, sem manipular history. | Nenhum. | Histórico não deve ser reparado/reescrito nesta transição; objetos de sistema preservados. |

No PostgreSQL, triggers e índices/constraints pertencentes a tabela não recebem transferência independente como uma tabela. As funções atuais V4/V5 são PL/pgSQL sem `SECURITY DEFINER`; a transição deve abortar se `prosecdef`/definição diferir. Não se deve adicionar `search_path` ou alterar corpo nessa etapa: se uma futura função SECURITY DEFINER for proposta, exigirá análise separada de `search_path` fixo, schema qualificado e EXECUTE de PUBLIC. Testes devem tentar chamar as funções e modificar/desabilitar triggers com runtime/bootstrap. A proteção só é aceita se as negações observadas continuarem iguais às provas T-03. Para cada objeto da aplicação, a ACL final deve ser comparada integralmente com a allowlist desta matriz; remover grants residuais do owner legado, `PUBLIC` e roles não previstas apenas nos objetos inventariados, sem alterar atributos LOGIN da role legada em nível de cluster. Se a origem do grant não puder ser classificada, abortar.

Referências primárias para validar as regras PostgreSQL 16 propostas: [role membership e opções SET/INHERIT/ADMIN](https://www.postgresql.org/docs/16/role-membership.html), [CREATE ROLE e CREATEROLE](https://www.postgresql.org/docs/16/sql-createrole.html), [ownership e privilégios de objetos](https://www.postgresql.org/docs/16/ddl-priv.html). A semântica efetiva ainda deve ser provada com as identidades de fixture antes da implementação.

##### D-10 — Estados parciais e recuperação

| Estado encontrado | Detecção / retomada | Ação permitida | Aborto |
|---|---|---|---|
| Falha antes do primeiro commit/journal | Não há journal e estado inicial permanece exatamente igual ao relatório aprovado. | Reiniciar com nova confirmação vinculada ao mesmo fingerprint ainda válido. | Se houve qualquer mudança sem journal. |
| V1–V4: criação de roles abortada antes do commit | Transação também desfaz journal inicial e roles criadas nessa transação. | Reexecutar após confirmar banco/roles iguais ao inventário aprovado. | Qualquer role parcial preexistente ou objeto global divergente. |
| Queda após commit `ROLES_READY` | Journal e conjunto exato de roles novos devem existir; credenciais nunca são relidas do journal. | Revalidar atributos/memberships, testar conexões via canal protegido e prosseguir. | Role ausente, atributo/membership/credencial não validável ou operador alterado. |
| Falha na validação de extensão | Journal registra etapa, sem alteração de ownership/grants; `pgcrypto` deve continuar com owner/schema/versão originais. | Corrigir externamente o pré-requisito e iniciar nova aprovação/inventário se isso mudar estado. | Extensão ausente/divergente ou tentativa de criá-la como migrator/runtime. |
| Transferência parcial de ownership | Comparar cada OID/identidade e owner com o evento correspondente do journal. | Transferir apenas próximo objeto esperado se cada anterior confere com evento e cada posterior ainda tem owner legado. | Owner sem evento, evento sem objeto, objeto renomeado/recriado/extra ou ordem não prefixo. |
| Falha durante aplicação de grants | Aplicar toda matriz de grants numa transação e gravar `GRANTS_DONE` no mesmo commit. | Se rollback comprovado, repetir a etapa; se commit presente, verificar estado e avançar. | Grant não previsto, ACL divergente, privilege herdado inesperado ou checkpoint inconsistente. |
| Queda entre etapa e checkpoint | Impossível dentro de etapa projetada: alteração e evento têm mesmo commit. | Reconciliar commit efetivo pelo journal e catálogos. | Se operação foi executada fora da transação/procedimento ou não há prova atômica. |
| Commit ocorrido, cópia externa do checkpoint ausente | Ler journal PostgreSQL, validar hash/destino/versão e reconciliar arquivo como cópia incompleta. | Continuar somente com estado efetivo exatamente igual ao checkpoint. | Qualquer mismatch entre journal e objetos. |
| Reexecução concluída | Journal removido atomicamente após verificação final; comparar Flyway, objetos, dados, roles, extensões e grants finais. | Retornar `ALREADY_COMPLETE`, sem DDL/DML, somente se equivalência integral for provada. | Ausência de journal com estado não-final ou evidência incompleta. |
| Estado parcial desconhecido ou journal alterado | UUID, sequência, eventos, checksums, objeto/destino/versão não reconhecidos ou alterações não descritas. | Nenhuma mutação automática; diagnóstico e plano novo aprovados. | Sempre. Não tentar reparar, limpar journal ou voltar owners automaticamente. |

Recuperação forward-only: manter API fora do corte até sucesso final; preservar journal e logs em falha; não retornar à credencial antiga privilegiada como fallback. Restaurar backup é decisão operacional externa, avaliada por perda de transações, nunca rollback automático do procedimento.

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
