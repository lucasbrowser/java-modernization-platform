# Spec-Driven Development (SDD)

Este diretório organiza mudanças orientadas por especificações. Não é uma lista de funcionalidades que precisam ser implementadas: primeiro, o Codex deve descobrir o que o repositório já faz.

## Princípio de segurança
**Nenhuma implementação é autorizada apenas pela existência de um arquivo de especificação.** O usuário aprova o plano e autoriza cada tarefa individualmente.

## Fluxo obrigatório
1. **Discover** — inspecionar Git, código, configurações, migrations, documentação e testes; registrar o que existe.
2. **Specify** — documentar o comportamento desejado e critérios de aceitação, separando fatos de hipóteses.
3. **Plan** — propor a abordagem técnica com arquivos afetados, riscos e testes.
4. **Approve** — aguardar aprovação explícita do usuário para o plano e para a tarefa específica.
5. **Implement** — executar apenas a tarefa aprovada.
6. **Verify** — executar testes e verificar critérios de aceitação.
7. **Review** — apresentar diff e resultados; aguardar aprovação antes da próxima tarefa.

## Estrutura recomendada
Cada iniciativa tem um identificador único e uma pasta própria:

```text
specs/
  README.md
  _template/
    spec.md
    plan.md
    tasks.md
  001-nome-da-iniciativa/
    spec.md
    plan.md
    tasks.md
```

Use nomes curtos em kebab-case. Não reutilize um identificador já existente.

## Status sugeridos
- `proposta`: ainda não aprovada.
- `em análise`: o Codex está investigando o estado atual.
- `aguardando aprovação`: especificação/plano prontos para revisão.
- `aprovada`: usuário aprovou o escopo/plano.
- `em andamento`: uma tarefa específica foi autorizada.
- `bloqueada`: depende de decisão, ambiente ou informação.
- `concluída`: critérios verificados e evidências registradas.
- `não necessária`: o comportamento já existe e atende aos requisitos, com evidência documentada.

Não marque status como aprovado ou concluído por inferência.

## Evitar trabalho duplicado
Antes de propor uma nova implementação, procurar:
- classes, interfaces, controllers, services, repositories e filtros existentes;
- configurações em `application*.yml`/`properties` e classes de configuração;
- dependências e plugins no `pom.xml`;
- migrations Flyway;
- testes unitários, de integração e de controller;
- documentação, Docker Compose e observabilidade existente.

Uma implementação existente pode precisar de testes ou documentação, não de uma segunda implementação. Registre a evidência antes de propor qualquer mudança.
