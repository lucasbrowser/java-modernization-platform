# Tarefas: Contrato de erros HTTP da API

As tarefas abaixo são propostas para execução após aprovação explícita. A aprovação da iniciativa não autoriza automaticamente cada tarefa, conforme o fluxo de `specs/README.md`.

## T-01 — Caracterizar cenários HTTP e fechar textos genéricos

- **Dependências:** aprovação explícita da iniciativa e desta tarefa.
- **Escopo:** criar cobertura de integração HTTP determinística para JSON malformado, tipo incompatível e falha inesperada controlada; registrar comportamento atual observado e definir textos genéricos estáveis, sem expor detalhes internos.
- **Arquivos prováveis:** testes de integração sob `src/test/java/com/github/lucasoliveira/platform/`; possivelmente `GlobalExceptionHandlerTest`.
- **Critérios de conclusão:** os testes exercitam o pipeline HTTP real; confirmam os status esperados; o cenário inesperado verifica que mensagem sentinela não aparece; segurança não é capturada pelo caminho genérico; fica confirmado se `ApiError` pode ser reutilizado sem mudança.
- **Testes previstos:** integração HTTP dos três cenários; testes pertinentes de segurança como regressão. Registrar o resultado sem alterar produção nesta tarefa de caracterização, se a separação for mantida na implementação aprovada.

## T-02 — Mapear erros de leitura JSON para 400

- **Dependências:** T-01 concluída e aprovação específica de T-02.
- **Escopo:** tratar erros de JSON malformado e tipo incompatível com corpo `ApiError` e mensagem genérica; não incluir detalhes do parser.
- **Arquivos prováveis:** `GlobalExceptionHandler.java` e testes HTTP de T-01.
- **Critérios de conclusão:** ambos os cenários retornam HTTP 400; corpo contém os campos atuais de `ApiError`; nenhuma mensagem interna do parser é exposta; validação Bean Validation permanece distinta e inalterada.
- **Testes previstos:** testes de integração HTTP dos dois tipos de falha e regressões para Bean Validation.

## T-03 — Mapear exceções inesperadas para 500 sem vazamento

- **Dependências:** T-01 concluída; T-02 pode ser executada antes ou em paralelo somente se a aprovação específica autorizar e não houver conflito nos mesmos arquivos.
- **Escopo:** resposta genérica HTTP 500 para exceções inesperadas abrangidas pelo handler; não implementar tratamento amplo de falhas de persistência além do necessário para a regra genérica acordada.
- **Arquivos prováveis:** `GlobalExceptionHandler.java` e testes HTTP de T-01.
- **Critérios de conclusão:** resposta `ApiError` com mensagem fixa genérica; corpo não contém mensagem da exceção, stack trace, SQL, nome de classe ou dado sensível; mapeamentos específicos existentes continuam prevalecendo; segurança mantém seu comportamento.
- **Testes previstos:** integração HTTP com exceção determinística portando texto sentinela; regressão de exceções específicas e segurança.

## T-04 — Verificar regressões e critérios da iniciativa

- **Dependências:** T-02 e T-03 concluídas e aprovadas.
- **Escopo:** verificar a matriz completa de erros no escopo, comparar status, campos e formatos atuais, e executar os testes pertinentes acordados.
- **Arquivos prováveis:** testes de handler, testes HTTP e, apenas se necessário, documentação desta iniciativa para registrar evidências.
- **Critérios de conclusão:** CA-01 a CA-10 avaliados; erros existentes e `fields` de validação preservados; respostas de sucesso e regras de negócio sem mudança; resultados e limitações registrados; nenhuma tarefa marcada como concluída sem evidência.
- **Testes previstos:** testes unitários do `GlobalExceptionHandler`, testes de integração HTTP novos e regressões pertinentes de auth/customer/product/order. Rodar a suíte acordada na aprovação da tarefa.

## Ordem e dependências

`T-01 → T-02 → T-03 → T-04` é a sequência recomendada. T-02 e T-03 compartilham o handler; qualquer paralelismo requer aprovação explícita e coordenação de edição. Não há migration nem alteração de schema prevista.

## Estado inicial antes da execução da T-01

Todas as tarefas estão **propostas / aguardando aprovação**. Nenhum teste de implementação foi executado e nenhuma tarefa foi aprovada ou concluída nesta iniciativa.

## Resultado da T-01 — caracterização parcial com limitações registradas

**Execução:** 2026-10-21. Nenhum código de produção foi alterado.

### Testes adicionados

- `src/test/java/com/github/lucasoliveira/platform/ApiErrorContractIntegrationTest.java`
  - JSON sintaticamente malformado em `POST /auth/login`: MockMvc retornou 400 e resolveu `HttpMessageNotReadableException`; corpo vazio, sem `ApiError`.
  - JSON válido com objeto no campo `password` (esperado `String`): MockMvc retornou 400 e resolveu `HttpMessageNotReadableException`; corpo vazio, sem `ApiError`.
  - `IllegalStateException` controlada pelo mock do `AuthService`, contendo sentinela/SQL/nome de classe: escapou do MockMvc como `ServletException`. Como não houve resposta serializada, este teste não afirma que o HTTP 500 está protegido contra vazamento.

MockMvc foi escolhido como alternativa próxima porque os ensaios com servidor aleatório retornaram 401 nas duas rotas de auth e na rota protegida de sonda, inclusive com JWT gerado pelo `JwtService`. A causa não foi identificada. Não houve alteração de segurança para forçar a passagem. Assim, status/corpo externos desses cenários permanecem pendentes para T-02.

### Mapeamentos existentes revisados

- A execução direcionada incluiu `AuthControllerIntegrationTest` (6 testes) e `GlobalExceptionHandlerTest` (5 testes). Isso cobre via MVC os fluxos de credenciais inválidas, e-mail registrado e validação, e diretamente no handler os casos de recurso inexistente, argumento inválido e optimistic locking.
- Nenhuma expectativa nem implementação de produção foi modificada.

### Comandos e resultados reais

- `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" "-Dtest=ApiErrorContractIntegrationTest,AuthControllerIntegrationTest,GlobalExceptionHandlerTest" test` — BUILD SUCCESS; 14 testes, 0 falhas, 0 erros, 0 ignorados.
- `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" clean test` — BUILD SUCCESS; 118 testes, 0 falhas, 0 erros, 0 ignorados. PostgreSQL/Testcontainers foi executado.
- Durante a caracterização por servidor aleatório, uma execução separada de `ApiErrorContractIntegrationTest` terminou com 3 falhas, todas por resposta 401 em vez dos status esperados. Esse ensaio foi substituído pela alternativa MockMvc e permanece registrado como limitação, não como teste aprovado.

### Textos propostos e pendências para T-02

- Texto genérico proposto para JSON não legível: `Malformed request body`.
- Mensagem genérica proposta para exceção inesperada: `An unexpected error occurred.`.
- Esses textos não foram observados como respostas atuais e ainda não foram validados por asserção de resposta HTTP; devem ser implementados e testados na T-02.
- T-02 deve testar respostas HTTP efetivas para os dois erros de leitura e para a exceção inesperada, confirmar `ApiError` nos 400/500, e provar que a resposta 500 não contém a sentinela, SQL, mensagem interna, stack trace, nome de classe ou dado sensível.
- A estratégia do teste por servidor deve resolver o 401 de teste sem enfraquecer a segurança de produção. Preservar também regressões de sucesso, erros específicos e validação por campo.

**Estado da T-01:** caracterização MVC adicionada e executada; comportamento HTTP completo do servidor não confirmado. A limitação foi documentada; a mensagem 500 permanece proposta e não testada como resposta. Não iniciar T-02 sem nova aprovação explícita.

## Resultado da T-02 — tratamento consistente de erros HTTP

**Status:** concluída nesta execução aprovada, após verificação direcionada e suíte completa.

### Implementação

- `GlobalExceptionHandler` agora mapeia `HttpMessageNotReadableException` para 400, `error=Bad Request`, `message=Malformed request body`, `fields=null`.
- Exceções inesperadas abrangidas retornam 500, `error=Internal Server Error`, `message=An unexpected error occurred.`, `fields=null`. A mensagem não deriva da exceção.
- O handler repropaga `AccessDeniedException` e `AuthenticationException`, preservando o tratamento de segurança. Também repropaga `ServletException` e `TypeMismatchException` para que o resolver MVC conserve status padrão desses erros de requisição.
- `ApiError` e `SecurityConfig` não foram alterados. Não foram criados endpoints de produção.

### Testes adicionados/adaptados

- `ApiErrorContractIntegrationTest` verifica JSON malformado e tipo incompatível (400, corpo `ApiError` e texto genérico), exceção inesperada controlada (500, `ApiError`, mensagem genérica e ausência de sentinela/SQL/nome de classe/stack trace) e preservação dos detalhes de validação por campo.
- `AuthControllerIntegrationTest` e `GlobalExceptionHandlerTest` foram executados como regressão. Os testes de controller de customer, product e order foram incluídos para cobrir autorização e status existentes.
- A execução é MockMvc no pipeline MVC com `GlobalExceptionHandler` e `SecurityConfig` efetivos. O teste socket da T-01 retornou 401; por isso, não foi usado para os novos cenários. Como os erros inesperados agora são tratados dentro do advice e os corpos são `ResponseEntity` desse handler, MockMvc verifica os status e corpos resultantes do handler. A política de segurança de produção não foi alterada.

### Resultados reais

- Primeira execução direcionada: 45 testes, 1 falha; `OrderControllerIntegrationTest.shouldReturnBadRequestWhenIdempotencyKeyIsMissing` mostrou regressão 400→500 causada pelo catch-all.
- Após repropagar exceções Servlet e de tipo, execução direcionada: comando `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" "-Dtest=ApiErrorContractIntegrationTest,AuthControllerIntegrationTest,GlobalExceptionHandlerTest,CustomerControllerIntegrationTest,ProductControllerIntegrationTest,OrderControllerIntegrationTest" test` — BUILD SUCCESS; 45 testes, 0 falhas, 0 erros, 0 ignorados.
- Suíte completa: `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" clean test` — BUILD SUCCESS; 119 testes, 0 falhas, 0 erros, 0 ignorados, incluindo PostgreSQL/Testcontainers.

### Limitações

- Os cenários novos verificam status/corpo por MockMvc, não por socket HTTP. A resposta 401 da tentativa socket da T-01 permanece sem causa completamente determinada; não se modificou a segurança para contorná-la.
- Não foi testado um catálogo exaustivo de todas as exceções do Spring/banco; tratamento amplo de falhas de persistência permanece fora do escopo.

## Resultado da T-03 — regressão final e fechamento da iniciativa

**Status:** T-03 concluída; iniciativa `003-api-error-contract` concluída conforme os critérios cobertos por testes e documentação. Nenhum código de produção ou teste foi alterado nesta tarefa.

### Matriz de critérios e evidências

- **JSON malformado e JSON válido incompatível com o DTO (CA-01 a CA-04):** `ApiErrorContractIntegrationTest` verifica, por MockMvc, status 400 e corpo compatível com `ApiError`, incluindo a mensagem estável `Malformed request body`.
- **Validação por campo:** `ApiErrorContractIntegrationTest.shouldPreserveFieldValidationDetails` verifica status 400 e detalhes em `fields`.
- **Exceção inesperada:** `ApiErrorContractIntegrationTest` verifica 500, `ApiError`, mensagem `An unexpected error occurred.` e ausência da sentinela, SQL, mensagem interna e nome de classe/stack trace.
- **Recurso inexistente, argumentos inválidos e optimistic locking:** `GlobalExceptionHandlerTest` cobre 404, 400 e 409, respectivamente.
- **E-mail já registrado e credenciais inválidas:** `AuthControllerIntegrationTest` cobre os status 409 e 401.
- **Regressões de controllers e segurança:** os testes de auth, customer, product, order e segurança foram executados; `OrderControllerIntegrationTest.shouldReturnBadRequestWhenIdempotencyKeyIsMissing` protege a preservação do 400 pelo resolver MVC.
- **Contrato de `ApiError`:** sua forma existente é usada pelos testes e pelo handler; não foi necessária alteração em `ApiError`.
- **Sucesso/regras de negócio:** nenhum código foi alterado nesta T-03; a suíte completa passou.

Os novos cenários de erro verificam respostas produzidas no pipeline MVC com MockMvc, `GlobalExceptionHandler` e `SecurityConfig` efetivos. Isso não equivale a uma chamada por socket HTTP real. As tentativas com servidor aleatório da T-01 retornaram 401, com causa não determinada; permanece risco residual de não haver confirmação ponta a ponta através do container. Não se declara cobertura por socket.

### Comandos e resultados reais da T-03

- `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" "-Dtest=ApiErrorContractIntegrationTest,AuthControllerIntegrationTest,GlobalExceptionHandlerTest,CustomerControllerIntegrationTest,ProductControllerIntegrationTest,OrderControllerIntegrationTest,ProductOptimisticLockIntegrationTest,PostgresContainerIntegrationTest" test` — BUILD SUCCESS; 59 testes, 0 falhas, 0 erros, 0 ignorados.
- `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" clean test` — BUILD SUCCESS; 119 testes, 0 falhas, 0 erros, 0 ignorados. Incluiu `PostgresContainerIntegrationTest` (13 testes).

### Estado final

T-01, T-02 e T-03 estão concluídas segundo seus resultados registrados. Os critérios da iniciativa estão relacionados a testes e os mapeamentos preservados foram confirmados. O risco residual é a ausência de verificação dos novos cenários por socket HTTP; os status e corpos declarados acima são evidência de MockMvc/pipeline MVC. Não há defeito comprovado pendente no escopo nem alteração de `spec.md` ou `plan.md` necessária para corrigir os critérios: este registro final esclarece o limite de execução que se aplica às afirmações de cobertura.
