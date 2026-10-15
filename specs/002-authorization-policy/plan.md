# Plano técnico: Registro documental da política de autorização

## Estado atual inspecionado

- `specs/README.md` define o processo SDD e recomenda uma pasta por iniciativa contendo `spec.md`, `plan.md` e `tasks.md`.
- A iniciativa existente é `specs/001-observability/`; não foi encontrada especificação de autorização de domínio em `specs/`.
- O cadastro de produtos está restrito por `@PreAuthorize("hasRole('ADMIN')")` no método `POST /products`. Os GETs de produtos não têm restrição por perfil.
- Clientes e pedidos dependem da autenticação geral da cadeia principal, sem restrição por perfil identificada nos controllers inspecionados.
- A cadeia principal permite os matchers de autenticação, Swagger e health declarados em `SecurityConfig`; `/actuator/prometheus` usa cadeia HTTP Basic dedicada.
- Testes de integração de produtos cobrem JWTs ADMIN e USER nos cenários da especificação. O teste de segurança do Prometheus usa MockMvc e credenciais de teste.
- A SDD-SEC-04 relatou historicamente 87 testes sem falhas, erros ou ignorados. Nenhum resultado de teste desta iniciativa será declarado.

## Estratégia

1. Criar uma nova iniciativa em `specs/002-authorization-policy/`, preservando o identificador existente `001-observability`.
2. Registrar em `spec.md` as decisões aprovadas, as observações de código, a matriz classificada, as questões pendentes e as evidências históricas.
3. Registrar neste `plan.md` o escopo estritamente documental, os riscos e a verificação prevista.
4. Registrar em `tasks.md` uma tarefa documental verificável, sem criar tarefa implícita de implementação de autorização.
5. Não alterar documentos existentes, código, testes, configurações, dependências, banco ou migrations.

## Arquivos a criar

- `specs/002-authorization-policy/spec.md`: política e critérios de aceitação.
- `specs/002-authorization-policy/plan.md`: estratégia, escopo, riscos e verificação desta iniciativa.
- `specs/002-authorization-policy/tasks.md`: tarefa documental com associação aos critérios.

Nenhum arquivo existente precisa ser modificado. `specs/README.md` não possui índice de iniciativas que exija atualização.

## Riscos e limites

- A política de propriedade dos pedidos não foi decidida; transformar comportamento atual em regra de propriedade extrapolaria as decisões aprovadas.
- Coincidência de emails entre `User` e `Customer` não comprova identidade nem vínculo.
- A leitura dos arquivos descreve os matchers de segurança, mas não prova que cada rota Swagger ou endpoint Actuator esteja efetivamente exposto em todo perfil de execução.
- O resultado de 87 testes é histórico da SDD-SEC-04, não evidência desta iniciativa.
- O executável Git não está disponível neste ambiente, então o estado committed/uncommitted não pode ser confirmado nesta etapa. Nenhuma alteração existente será sobrescrita intencionalmente.

## Verificação documental

- Conferir que os três documentos existem no caminho aprovado.
- Revisar a matriz e confirmar a coluna de classificação.
- Conferir a separação entre decisões aprovadas, comportamento observado e questões pendentes.
- Confirmar que o resultado de 87 testes está explicitamente rotulado como histórico.
- Confirmar que nenhuma credencial é registrada e que nenhuma política de propriedade ou nova regra administrativa foi inventada.
- Não executar Maven: esta iniciativa é documental e não altera código ou testes.
