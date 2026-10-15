# Especificação: Política atual de autorização

## 1. Objetivo

Registrar a política de autorização aprovada e o comportamento observado nas rotas atuais, distinguindo decisões aprovadas de fatos de implementação e de decisões de negócio pendentes.

Esta especificação não autoriza, por si só, mudanças de código.

## 2. Escopo

- Registrar a autorização para cadastro e consulta de produtos.
- Registrar o comportamento atual de autenticação para clientes e pedidos, sem introduzir restrições por perfil.
- Registrar as rotas públicas e a autenticação Basic do endpoint Prometheus.
- Identificar como pendentes a autorização por propriedade de pedidos e um eventual vínculo entre `User` e `Customer`.

## 3. Decisões aprovadas

1. `POST /products` é restrito ao perfil `ADMIN`.
2. `GET /products` e `GET /products/{id}` permanecem acessíveis a usuários `ADMIN` e `USER` autenticados.
3. As permissões atuais das operações de clientes permanecem inalteradas.
4. As permissões atuais das operações de pedidos permanecem inalteradas.
5. A autorização por propriedade de pedidos ainda não foi definida.
6. Não se deve presumir que `User` e `Customer` representam a mesma pessoa apenas porque seus emails coincidem.
7. Esta iniciativa não introduz novas regras administrativas.
8. As rotas públicas e a autenticação Basic do Prometheus mantêm o comportamento atual.

## 4. Comportamento observado

Esta seção registra o código inspecionado; não transforma observações em requisitos novos.

- `SecurityConfig` habilita segurança por método com `@EnableMethodSecurity`.
- A cadeia principal permite `/auth/**`, rotas Swagger configuradas e `/actuator/health` com seus subpaths. As demais requisições dessa cadeia exigem autenticação.
- A cadeia específica de `/actuator/prometheus` exige autenticação HTTP Basic.
- O filtro JWT lê a claim `role` e a converte em autoridade `ROLE_<perfil>`. O enum da aplicação define `ADMIN` e `USER`.
- O método `create` de `ProductController`, mapeado por `POST /products`, tem `@PreAuthorize("hasRole('ADMIN')")`. Os métodos de consulta não têm essa restrição por perfil.
- As operações atuais de `CustomerController` e `OrderController` não declaram restrições por perfil. Na cadeia principal, elas ficam sujeitas à autenticação geral.
- `Order` possui relação com `Customer`. O modelo inspecionado não apresenta relação entre `User` e `Customer`; o fluxo de pedidos não aplica verificação de propriedade baseada no usuário autenticado.

## 5. Matriz de autorização

“Decisão aprovada” identifica política aprovada. “Comportamento observado” descreve apenas as regras presentes no código inspecionado.

Os caminhos de controllers na matriz são mappings relativos ao `server.servlet.context-path`. No servidor HTTP principal, configurado com prefixo `/api`, o mapping `POST /products` é acessado externamente como `POST /api/products`. A matriz mantém os mappings para facilitar a correspondência com os controllers. Os paths dos matchers Actuator são apresentados como definidos na configuração; o scrape usa o servidor de gerenciamento configurado separadamente.

| Rota/operação | Acesso registrado | Classificação | Evidência/observação |
|---|---|---|---|
| `POST /products` | JWT autenticado com perfil `ADMIN` | Decisão aprovada | `@PreAuthorize("hasRole('ADMIN')")` no método de cadastro; testes de integração usam JWT ADMIN. |
| `GET /products` | JWT autenticado com perfil `ADMIN` ou `USER` | Decisão aprovada | Testes de integração exercitam ambos os perfis. |
| `GET /products/{id}` | JWT autenticado com perfil `ADMIN` ou `USER` | Decisão aprovada | Testes de integração exercitam ambos os perfis. |
| Operações existentes de `/customers` | Autenticação exigida pela cadeia principal; sem regra por perfil identificada | Comportamento observado; permissões mantidas por decisão aprovada | Não inferir restrições por perfil ou propriedade. |
| Operações existentes de `/orders` | Autenticação exigida pela cadeia principal; sem regra por perfil identificada | Comportamento observado; permissões mantidas por decisão aprovada | Política de propriedade permanece pendente. |
| `/auth/**` | Permitido pela cadeia principal | Comportamento observado | Os endpoints de registro e login estão sob esse prefixo. |
| `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**` | Permitido pela cadeia principal | Comportamento observado | A autorização da cadeia não afirma que todo caminho tenha handler ativo. |
| `/actuator/health` e `/actuator/health/**` | Permitido pela cadeia principal | Comportamento observado | A exposição efetiva também depende da configuração do Actuator. |
| `/actuator/prometheus` | HTTP Basic autenticado | Decisão aprovada de preservação e comportamento observado | Matcher dedicado com `httpBasic` e exigência de autenticação. Credenciais não são registradas nesta especificação. |
| Demais requisições da cadeia principal | Autenticação exigida | Comportamento observado | Sem regra geral por perfil além das restrições de método documentadas acima. |

Autenticação não implica propriedade sobre recursos. A tabela não define novas permissões para métodos ou rotas que não estejam listados no código atual.

## 6. Decisões pendentes

As questões abaixo não alteram as permissões atuais e não autorizam implementação:

- Se um usuário autenticado deve acessar somente pedidos associados a um cliente sob sua responsabilidade.
- Como estabelecer e manter um eventual vínculo entre `User` e `Customer`, caso uma política de propriedade seja aprovada.
- Qual política se aplicaria a usuários `ADMIN` em consultas de pedidos, caso uma regra de propriedade venha a ser definida.
- Qual resposta HTTP deve ser usada para uma futura tentativa de acesso a pedido sem propriedade confirmada.

Emails iguais, isoladamente, não provam identidade nem estabelecem vínculo aprovado entre `User` e `Customer`.

## 7. Evidências de teste

Os arquivos de teste existentes incluem:

- `ProductControllerIntegrationTest`: criação permitida com JWT ADMIN; criação negada com `403` para JWT USER e sem chamada ao serviço; rejeição `401` para criação anônima e Bearer inválido; consultas por JWT ADMIN e USER; validação de payload inválido. Usa `@WebMvcTest`, importa `SecurityConfig` e exercita JWT no contexto MVC de teste; não inicializa a aplicação completa.
- `CustomerControllerIntegrationTest` e `OrderControllerIntegrationTest`: os cenários autenticados usam `.with(user(...))`, que simula a autenticação. Esses cenários não demonstram o percurso do filtro JWT para esses controllers.
- `PrometheusSecurityIntegrationTest`: rejeição de credenciais Basic ausentes ou inválidas e resposta aceita para credenciais de teste válidas, usando MockMvc. O teste importa `SecurityConfig`, mas usa `@WebMvcTest` com `MetricsTestEndpoint`, um endpoint de teste; não comprova o scrape integrado ao Actuator e ao Prometheus reais. A validação integrada real permanece pendente, conforme registrada em T-02 de `specs/001-observability/tasks.md`.

**Histórico informado na conclusão da SDD-SEC-04:** 87 testes, zero falhas, zero erros e zero testes ignorados. Esse resultado é histórico, foi relatado para aquela execução e não representa execução ou validação realizada nesta iniciativa.

## 8. Critérios de aceitação

- CA-01: decisões aprovadas, comportamento observado e decisões pendentes aparecem em seções distintas.
- CA-02: a matriz inclui classificação e não amplia as regras atuais de autorização.
- CA-03: a restrição ADMIN aplica-se somente ao cadastro de produtos; as consultas permanecem acessíveis a ADMIN e USER autenticados.
- CA-04: permissões de clientes e pedidos permanecem inalteradas; nenhuma política de propriedade é presumida.
- CA-05: igualdade de email não é tratada como prova de identidade entre `User` e `Customer`.
- CA-06: rotas públicas e Basic do Prometheus refletem o comportamento observado, sem registrar credenciais.
- CA-07: referências de testes descrevem os cenários existentes e identificam 87 testes apenas como resultado histórico da SDD-SEC-04.
- CA-08: os documentos não alteram código, testes, configurações ou comportamento da aplicação.

## 9. Fora do escopo

- Alterar as regras de autorização ou autenticação existentes.
- Definir ou implementar autorização por propriedade de pedidos.
- Criar funcionalidades administrativas.
- Alterar código, testes, dependências, configurações, banco de dados ou migrations.
- Reexecutar ou certificar resultados de testes anteriores.
