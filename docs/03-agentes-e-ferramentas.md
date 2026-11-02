# 03 — Agentes e ferramentas

## Três camadas de instrução (cada uma no seu lugar)

| Camada | Onde vive | Quem muda | Exemplo |
|---|---|---|---|
| Instruções do agente | `AgentDefinition` no catálogo (versionado, por tenant) | Dono do agente (Agent Studio) / personalização | "Você é {{nomeAgente}}, da {{loja}}; uma pergunta por mensagem; nunca promete valor de compra." |
| Objetivo da missão | `kikwi:prompt` no nó `AGENT_TASK` | Quem desenha o processo | "Qualifique o veículo de {{leadNome}} e agende a visita." |
| Contrato de saída | `variableBindings.outputs` do nó + catálogo | Quem desenha o processo | `resultado` (AGENDADO/PERDIDO/HUMANO), `dataAgendamento`, `fotosVeiculo` |

`SystemPromptAssembler` sempre junta as camadas nesta ordem:
1. instruções do agente, com os parâmetros da versão preenchidos;
2. objetivo da missão, com cada `{{variavel}}` do processo trocado por uma referência («variavel»);
3. **dados do atendimento**: os valores dessas variáveis, num bloco à parte, como dados — nunca colados na instrução;
4. regras de encerramento.

O `promptHash` (12 hex do SHA-256) vai para a auditoria. Assim dá para responder "com qual prompt esse lead foi
atendido?" mesmo depois de dez versões.

## Definição de agente (catálogo)

`AgentDefinition` — o mesmo JSON do `.agent.json` do Agent Studio (Craft): `key`, `version`, `status`, `name`,
`description`, `instructions` (texto livre com `{{parametros}}`), `parameters`, `parameterValues`, `model`
(`provider:model`, temperatura, máx. tokens), `perception`, `tools` (referências), `subagents` e `limits`.
- **Catálogo** (`AgentCatalog`, `agents-runtime`): rascunho editável + versões publicadas imutáveis, por tenant. A
  missão usa a versão fixada no nó ou a publicada mais recente; rascunho nunca roda. Persistência: Mongo
  (`agent_definitions`) ou memória (`kikwi-agents.catalog.store`).
- **Personalizar** = publicar uma nova versão só com outros `parameterValues` (nome do agente, loja, endereço).
- **API**: `/api/tenants/{tenantId}/agents` — listar, `GET /{key}`, `PUT /{key}/draft` (aceita o `.agent.json` do
  Studio), `POST /{key}/publish`, `POST /{key}/personalize`, `GET /{key}/versions/{v}`.
- Os agentes de exemplo (`agents-app/src/main/resources/agents/*.agent.json`) são publicados no tenant de
  demonstração na subida, se ainda não existirem.

## Ferramentas

O agente guarda **referências** (`AgentToolRef(name, type, processKey, description)`); o `AgentToolResolver` monta
as `ToolDefinition` que o modelo vê, a cada missão:
- `PROCESS` — um processo Kikwiflow. Descrição, schema de entrada (os `inputs` do start) e retorno (os `outputs`
  dos fins, `ProcessContract`) vêm do processo implantado (`EngineProcessTools`). Executar = iniciar a instância
  com o `businessKey` da missão; o modelo recebe **só o retorno** (`{"status":"CONCLUIDO","retorno":{...}}`).
  Entrada fora do contrato volta como erro de ferramenta para o modelo corrigir. V1: o processo precisa terminar
  na mesma chamada; se parar numa espera, volta `EM_ANDAMENTO`.
- `INLINE` — código do runtime registrado no `ToolRegistry`.
- `HTTP` / `MCP` — reservados; ainda sem executor.

As ferramentas que o agente **vê** são as dele restritas pelo `AGENT_TASK.tools` (vazio = todas), mais as de
controle. O processo restringe o agente, nunca o contrário. Para chamar a API de um cliente sem código, o processo-
ferramenta usa o conector `kikwiHttp` do motor (`kikwi-connector-http`).

Ferramentas de controle, tratadas pelo loop e sempre disponíveis:
- `concluir_missao`: input = contrato de saída (schema gerado por `OutputSchemaBuilder`). Se faltar campo
  obrigatório, o modelo recebe erro e precisa perguntar ao lead. A validação final é do motor.
- `escalar_humano(motivo)`: conversa em `HANDED_OFF` + `AGENT_HANDOFF` na auditoria.

Bindings (`ToolBinding`):

| Binding | Execução | Uso |
|---|---|---|
| `Inline(executorName)` | `ToolExecutor` registrado no `InlineBindingExecutor` | Lógica da plataforma (pedir mídia, consultar FIPE) |
| `Http(method, urlTemplate, credentialsRef)` | Chamada declarativa *(a implementar)* | Integrações do tenant sem código (ex.: agenda do cliente) |
| `Process(processKey, outputVariables)` | Inicia um processo Kikwiflow filho e aguarda *(a implementar)* | Ação de negócio que merece trilho próprio (ex.: "reservar horário" com aprovação) |

Toda chamada, de qualquer binding, gera **um** `AGENT_TOOL_INVOKED`: `toolId`, `binding`, `inputHash`, `outcome`
(`OK`/`ERROR`/`DENIED`) e `durationMs`. Ferramenta fora da interseção é negada e auditada como `DENIED`. Ferramentas
com `sideEffect` nunca são repetidas automaticamente e devem ser idempotentes do lado de quem as implementa.

## Fundação para o caminho "ad-hoc" (futuro)

No Camunda 8.8 o agente escolhe atividades de um *ad-hoc sub-process*. Aqui o caminho equivalente está preparado
sem estar construído:
1. o motor ganha `AD_HOC_SUBPROCESS`, cujas atividades internas são publicadas como ferramentas;
2. o runtime ganha um `BindingExecutor<Activity>`, que dispara a atividade como token real do processo e aguarda;
3. **nada muda** no `AgentTurnProcessor`, no `ToolRegistry`, no evento de auditoria nem na definição do agente.

## Loop de um turno (`AgentTurnProcessor`)

1. Ferramentas visíveis + `concluir_missao` + `escalar_humano`.
2. Transcrição para mensagens do LLM: papéis consecutivos unidos, anexos viram `[anexo recebido: nome (mime) dms://...]`
   e mensagens de humano levam o prefixo `[Atendente humano]`.
3. Até `maxToolIterations` (padrão sugerido: 6):
   - resposta sem ferramenta: `Reply`;
   - `concluir_missao` completo: `Completed`;
   - `escalar_humano`: `HandedOff`;
   - demais ferramentas: executar, auditar e devolver o resultado.
4. Estourou o limite: `Exhausted` (falha técnica, com retry e depois incidente).

## Worker de missões (`MissionWorker`)

| Situação | O que o worker faz |
|---|---|
| Missão adquirida | Liga a conversa (`activeMissionTaskId`), reporta `AGENT_MISSION_STARTED` e roda o primeiro turno. Se o lead não falou desde a última mensagem do agente (recuperação, retomada), uma nota de sistema só no pedido ao LLM faz o agente puxar a conversa. |
| Perfil ou conversa inexistente | Falha sem retry: incidente no Monitor (erro de configuração). |
| `Reply` | Grava na transcrição, envia pelo canal e renova o lease. |
| `Completed` | `complete` com o relatório (`agentVersion`, `turns`, tokens) no `AGENT_MISSION_COMPLETED`; solta a conversa e envia a despedida. |
| Contrato recusado (422) | Grava uma nota `SYSTEM` na transcrição com as violações e roda o turno de novo; na 2ª recusa seguida, falha técnica. |
| `HandedOff` ou limite (`maxTurns`, `ttl`) | Conversa em `HANDED_OFF` e `complete` com `resultado = HUMANO`; o processo roteia. |
| `Exhausted` ou erro do LLM | `failure` com as tentativas restantes (`retries` da missão − 1, ou o padrão); depois, incidente. |
| Opt-out do lead | Erro de negócio `LEAD_OPT_OUT`; sem `BOUNDARY_ERROR_HANDLER` no nó, vira incidente. |
| Lease perdido (409/404) | Solta a conversa sem erro: o processo mandou (boundary) ou outro nó assumiu. |

O `maxTurns` efetivo é o menor entre o do nó e o do agente; o `ttl` conta da criação da missão no motor.

Pendências do runtime:
- envio de imagens ao modelo (resolver `dms://` → base64 quando o provedor tiver `VISION`);
- conversa que um humano assumiu pelo catch "humano assumiu" continua `OPEN` no runtime (a missão some, mas o
  runtime não sabe o motivo); o painel do consultor deve marcar `HANDED_OFF`;
- avaliação offline de prompts (conjunto de conversas de referência por agente).
