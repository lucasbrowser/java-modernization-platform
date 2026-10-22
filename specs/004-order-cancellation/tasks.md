# Tarefas: Cancelamento de pedidos com devolução de estoque

**Estado da iniciativa:** em andamento; T-01 a T-04 concluídas, T-05 aguarda autorização específica.  
**Regra SDD:** a aprovação do plano não conclui aprovação automática das tarefas; cada tarefa de implementação aguarda autorização específica.

## T-01 — Implementar a transição transacional no serviço

- **Estado:** concluída em 2026-10-22, com validação unitária e compilação limpa.
- **Dependências:** aprovação da iniciativa e autorização explícita desta tarefa.
- **Escopo:** consulta do pedido sob lock de escrita; elegibilidade por estado; autorização pela `OrderAuthorizationService`; devolução de quantidades por produto; alteração para `CANCELLED`; repetição sem efeitos duplicados; exceção de domínio específica para estado não cancelável.
- **Arquivos prováveis:** `OrderRepository.java`, `OrderService.java`, nova exceção do pacote `order`, `OrderServiceTest.java`.
- **Testes previstos:** pedido próprio/ADMIN; terceiro como não encontrado; inexistente; usuário sem vínculo; estado CREATED; repetição CANCELLED; estado CONFIRMED; pedido legado sem itens; vários itens do mesmo produto e vários produtos; estoque e status não alterados quando a regra rejeita a operação.
- **Critérios objetivos de conclusão:** serviço implementa as regras de estado/autorização; em transação, sucesso devolve quantidades e persiste CANCELLED; pedido sem itens é cancelado sem ajuste de estoque; repetição é no-op; estados não canceláveis falham com exceção específica sem mutação; testes unitários pertinentes passam. Ainda não afirmar concorrência ou rollback real de PostgreSQL nesta tarefa.

### Resultado executado da T-01 — 2026-10-22

- `OrderRepository.findByIdForUpdate(UUID)` usa uma consulta JPQL explícita anotada com `@Lock(LockModeType.PESSIMISTIC_WRITE)`. `OrderService.cancel` consulta o pedido por esse método antes de verificar propriedade/estado; o teste de serviço verifica o uso do método bloqueante. A anotação configura o lock JPA na consulta ao banco. Nesta tarefa não foi executado teste concorrente com PostgreSQL, portanto a garantia observada de serialização concorrente permanece pendente para T-04.
- O serviço é `@Transactional`, usa `Product.version` existente, altera status e estoque na mesma unidade de trabalho e chama `flush()` antes de retornar o resultado de cancelamento. `Math.addExact` impede overflow silencioso no estoque e propaga falha em vez de persistir valor incorreto.
- Testes adicionados verificam USER próprio e ADMIN, terceiro mascarado como pedido inexistente, escopo negado antes de carregar o pedido, pedido inexistente, estado `CONFIRMED` sem mutação, repetição de `CANCELLED` sem novo ajuste, pedido vazio e restauração de múltiplos itens/produtos.
- `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" clean "-Dtest=OrderServiceTest" test` — BUILD SUCCESS; compilou 48 fontes de produção e 22 fontes de teste; `OrderServiceTest`: 22 testes, 0 falhas, 0 erros, 0 ignorados.
- Uma tentativa do mesmo comando no sandbox falhou durante a leitura de `spring-security-web-6.3.4.jar` com `Acesso negado`; repetição com permissão elevada completou e passou. Nenhum código foi alterado para contornar o ambiente.
- Atomicidade/rollback após falha de persistência e serialização concorrente real continuam sem comprovação de integração PostgreSQL e permanecem para T-04. T-01 não alega esses resultados.

## T-02 — Expor o endpoint e o contrato de conflito

- **Estado:** concluída em 2026-10-22 nesta execução autorizada; T-03 a T-05 permanecem propostas.
- **Escopo:** adicionar `POST /orders/{id}/cancel`, retorno 200 com `OrderResponse` existente; mapear apenas a exceção nova para 409 `ApiError` com mensagem fixa; manter handlers atuais.
- **Arquivos prováveis:** `OrderController.java`, `GlobalExceptionHandler.java`, teste MVC de pedidos e, se necessário, teste unitário do handler.
- **Testes previstos:** sucesso e resposta sem campos novos; inexistente/terceiro 404 com mensagem igual; repetição 200; estado não cancelável 409 com `ApiError`; ID malformado para caracterizar status e corpo MVC; regressão dos handlers 400/401/403/404/409/500 já existentes.
- **Critérios objetivos de conclusão:** rota sem body e status/DTO conforme especificação; nova exceção dá 409 sem alterar outras exceções; resposta por terceiro é indistinguível de inexistente; testes de MVC passam.

### Resultado executado da T-02 — 2026-10-22

- `POST /orders/{id}/cancel` foi adicionado ao controller. Retorna HTTP 200 e o `OrderResponse` retornado pelo serviço, sem DTO/campos novos.
- `GlobalExceptionHandler` mapeia somente `OrderCancellationConflictException` a 409 `ApiError`, com texto fixo e `fields=null`. O texto da exceção não é serializado. Não houve tratamento global de `IllegalStateException` nem alteração dos handlers existentes.
- Testes MVC verificam sucesso, repetição com 200, conflito 409 e forma de `ApiError`, 404 e mensagem neutra para não encontrado/indisponível, ID UUID inválido (400 e corpo vazio no MockMvc), chamada anônima (401) e `IllegalStateException` alheia à nova exceção (500 genérico). Os testes MVC usam MockMvc com `SecurityConfig` e handler reais; não são chamadas por socket HTTP.
- Regressões incluíram os testes já existentes de validação e mapeamento do handler, além dos testes de serviço da T-01.
- `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" clean "-Dtest=OrderControllerIntegrationTest,GlobalExceptionHandlerTest,OrderServiceTest" test` — BUILD SUCCESS; compilou 48 fontes de produção e 22 fontes de teste; 42 testes, 0 falhas, 0 erros, 0 ignorados (15 controller, 5 handler, 22 service).
- Não houve alteração em autorização, configuração, entidade ou migration. T-03, T-04 e T-05 não foram iniciadas.

## T-03 — Validar autorização ponta a ponta com JWT

- **Estado:** concluída em 2026-10-22 nesta execução autorizada; T-04 e T-05 permanecem propostas.
- **Escopo:** integrar identidade persistida e segurança real à rota de cancelamento, sem enfraquecer `SecurityConfig`.
- **Arquivos prováveis:** teste dedicado de integração de pedidos, fixtures/utilidades de JWT existentes; produção somente se teste demonstrar defeito concreto de autorização.
- **Testes previstos:** não autenticado 401; USER vinculado cancela pedido próprio; USER sem vínculo 403; USER de terceiro 404 sem vazamento; ADMIN cancela pedido alheio; pedidos e estoque permanecem inalterados nos casos negados. Usar PostgreSQL/Testcontainers e token JWT real emitido pelo serviço quando apropriado.
- **Critérios objetivos de conclusão:** todos os status/escopos são comprovados pela cadeia real de filtro e identidade; nenhuma regra das iniciativas 002/003 é enfraquecida ou alterada indevidamente.

### Resultado executado da T-03 — 2026-10-22

- Adicionado `shouldEnforceCancellationAuthorizationThroughRealJwtAndPostgres` em `PostgresContainerIntegrationTest`. O teste usa o `JwtAuthenticationFilter`, `JwtService`, `SecurityConfig`, `AuthenticatedUserResolver`, repositórios e `OrderService` reais com PostgreSQL/Testcontainers; não usa mocks para autenticação/autorização.
- Observações HTTP da rota de cancelamento: anônimo `401`; USER persistido sem Customer `403`; USER vinculado cancelando pedido próprio `200` com `CANCELLED`; USER vinculado tentando cancelar pedido de outro Customer `404`; ADMIN cancelando pedido de outro Customer `200` com `CANCELLED`.
- A resposta do pedido de terceiro foi comparada com a de um UUID inexistente nos campos `status`, `error`, `message` e `fields`; os valores foram iguais (`404`, `Not Found`, `Order not found`, `null`). Timestamp não foi comparado por ser gerado por resposta.
- Um JWT válido e assinado pelo `JwtService`, cujo subject não corresponde a usuário persistido, chegou ao `AuthenticatedUserResolver`, que lançou `AuthenticatedUserNotFoundException`; o `GlobalExceptionHandler` atual retornou `500` com mensagem genérica. O pedido permaneceu `CREATED` e o estoque não mudou. Esse status é o comportamento efetivamente observado; não foi definida nesta tarefa uma política 401/403 para essa identidade sem registro.
- O teste também confirmou o estoque antes/depois dos pedidos negados e após os cancelamentos autorizados: o estoque não mudou por chamada negada; cada cancelamento permitido restaurou uma unidade.
- `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" clean "-Dtest=PostgresContainerIntegrationTest" test` — compilou 48 fontes de produção e 22 de teste; com permissão elevada, `BUILD SUCCESS`: `PostgresContainerIntegrationTest` executou 14 testes, 0 falhas, 0 erros, 0 ignorados, incluindo o novo cenário de autorização. A primeira tentativa sem permissão elevada não encontrou Docker; a repetição autorizada pôde iniciar Testcontainers.
- A integração usa MockMvc com o filtro JWT e o contexto Spring reais; não é uma chamada por socket HTTP. Não houve alteração de produção ou das políticas de autorização.

## T-04 — Provar atomicidade, concorrência e integridade no PostgreSQL

- **Estado:** concluída em 2026-10-22 nesta execução autorizada; T-05 permanece proposta.
- **Escopo:** testes de integração para rollback, estado/estoque persistidos, lock do pedido, concorrência de cancelamentos e concorrência com outras atualizações de estoque.
- **Arquivos:** `src/test/java/com/github/lucasoliveira/platform/order/OrderCancellationPostgresIntegrationTest.java`.
- **Testes previstos:** sucesso reflete CANCELLED e estoque; injetar falha determinística de persistência sem infraestrutura de produção e comprovar rollback integral; duas chamadas concorrentes restauram exatamente uma vez; versão Product preserva consistência com operação concorrente; pedido CONFIRMED/repetição não causam estoque duplicado.
- **Critérios objetivos de conclusão:** testes executados contra PostgreSQL/Testcontainers mostram estado final correto, nenhuma atualização parcial e restauração única sob concorrência; método de falha de teste não adiciona endpoint nem chave de produção.

### Resultado executado da T-04 — 2026-10-22

- Criada a classe `OrderCancellationPostgresIntegrationTest`, com PostgreSQL 16 via Testcontainers. As chamadas concorrentes usam duas threads, duas transações `TransactionTemplate` e conexões independentes. Um terceiro JDBC connection mantém a linha do pedido bloqueada enquanto ambas as chamadas iniciam; o teste aguarda a confirmação de duas sessões em espera de lock no `pg_stat_activity` antes de liberar o lock.
- Concorrência: pedido criado para quantidade 2 reduziu o estoque de 10 para 8. Depois de liberar o lock, ambas as operações retornaram `CANCELLED`; o pedido persistiu `CANCELLED` e o estoque final persistido foi 10. Uma terceira repetição posterior manteve o estoque em 10.
- Rollback: pedido com dois produtos partiu dos saldos persistidos 8 e 17. Uma trigger PostgreSQL `BEFORE UPDATE OF stock` permitiu a primeira atualização de estoque e levantou a falha configurada na segunda atualização durante o flush do cancelamento. O teste confirmou a mensagem da trigger na cadeia da exceção; após a transação falhar, o pedido permaneceu `CREATED`, os saldos permaneceram 8 e 17 e as versões `Product.version` permaneceram iguais às versões anteriores à tentativa.
- Regressão: `OrderConcurrencyIntegrationTest` e `ProductOptimisticLockIntegrationTest` também passaram, preservando a verificação existente de concorrência na criação e incremento de versão otimista de Product. Nenhum código de produção, configuração, controller, handler, política de autorização ou migration foi alterado.
- `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" clean "-Dtest=OrderCancellationPostgresIntegrationTest,OrderConcurrencyIntegrationTest,ProductOptimisticLockIntegrationTest" test` — `BUILD SUCCESS`; compilou 48 fontes de produção e 23 de teste; 4 testes, 0 falhas, 0 erros, 0 ignorados (2 novos cenários de cancelamento, 1 teste de concorrência de pedidos e 1 teste de versionamento otimista de Product).
- A tentativa inicial no sandbox não encontrou Docker. Uma primeira execução com PostgreSQL encontrou SKUs de fixture acima do limite de 50 caracteres; os identificadores foram encurtados e a execução final com Testcontainers passou. Não se alterou schema ou comportamento de produção para acomodar os testes.
- Os testes demonstram espera efetiva pelo lock `PESSIMISTIC_WRITE` no PostgreSQL, em duas transações de cancelamento concorrentes. Não houve teste concorrente de cancelamento simultâneo a uma operação independente sobre o mesmo `Product`; o controle otimista existente segue coberto pelo teste de produto já citado.

## T-05 — Regressão final e fechamento documental

- **Estado:** proposta; depende de T-01 a T-04 concluídas e aprovadas.
- **Escopo:** mapear critérios CA-01 a CA-14 para testes, revisar diff funcional, executar testes direcionados e suíte completa e registrar resultados reais nesta iniciativa.
- **Arquivos prováveis:** `spec.md`, `plan.md`, `tasks.md`; correções de produção/teste somente se autorizadas e se defeito for demonstrado.
- **Testes previstos:** testes unitários e de integração de pedido, concorrência de estoque, autorização, contrato de erro e suíte Maven completa definida na aprovação da tarefa.
- **Critérios objetivos de conclusão:** critérios aceitos estão rastreados a testes; todos os comandos têm resultado real registrado; nenhuma regressão ou limitação é ocultada; iniciativa só recebe estado concluído após evidência.

## Primeira tarefa de implementação

A primeira tarefa proposta é **T-01 — Implementar a transição transacional no serviço**. Sua conclusão objetiva exige: leitura/lock do pedido; verificação de propriedade/estado; alteração atômica do pedido e do estoque; repetição segura; exceção de domínio específica para estados não canceláveis; testes unitários passando. T-01 não pretende comprovar ainda o comportamento de lock concorrente/rollback no PostgreSQL, que fica para T-04.

## Histórico de execução

Nenhuma tarefa foi aprovada ou executada. Não houve alteração de código, migration, configuração ou teste, e nenhum comando de teste foi executado nesta rodada de planejamento.
