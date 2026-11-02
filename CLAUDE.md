# CLAUDE.md

Guia para o Claude Code neste repositório.

## Projeto

`kikwi-agents`: plataforma de agentes conversacionais multi-tenant (Java 21 / Spring Boot 3.5, Maven multi-módulo,
`io.kikwiflow.agents`) que embute o motor Kikwiflow (`../atoxfy`, versão em `kikwiflow.version` no POM raiz).
Publicado no Maven Central (`io.kikwiflow.agents`, mesmo processo de release do motor). Docs em português (`docs/`). Canais (site, WhatsApp) ficam fora daqui: no roteador de canais do produto
(`../kikwi-agents-platform`).

## Build e testes

```
mvn test                                     # todos os módulos, sem Mongo/rede
mvn -pl agents-runtime -am test              # um módulo e suas dependências
mvn -pl agents-app spring-boot:run           # app (MongoDB local; porta 8090)
```

Testes: JUnit 5 + AssertJ, estilo BDD com `@DisplayName` "Dado/Quando/Então" e `@Nested`, igual ao motor. LLM em
testes sempre via `ScriptedLlmClient` (`agents-llm-fake`), nunca rede.

## Regras de arquitetura (não quebrar)

- **O processo decide, o agente conversa.** Escalação, recuperação, handoff e desistência são rotas no `.kikwi`
  sobre a saída validada da missão (`resultado`), nunca lógica escondida no runtime ou no prompt.
- **Granularidade de missão.** Um nó `AGENT_TASK` = uma missão (objetivo + ferramentas + contrato de saída). O loop
  de conversa roda em `agents-runtime`; o motor não vê turnos. Ver `../atoxfy/docs/engine/31-orquestracao-agentica.md`.
- **Claim check.** Transcrição fica no repositório de mensagens; arquivos no GridFS (`gridfs://`) ou num DMS (`dms://`). Variáveis do processo e
  auditoria levam só referências e hashes, nunca conteúdo.
- **LLM agnóstico.** O runtime só conhece `LlmClient`/`LlmRouter`; o provedor sai do `ModelRef` (`provider:model`).
  Recurso específico de provedor entra por `LlmRequest.providerOptions`, não por métodos novos na porta. Adapters:
  `agents-llm-anthropic` (`anthropic:`) e `agents-llm-gemini` (`gemini:`), HTTP + Jackson sem SDK; dado opaco que o
  provedor exige de volta (assinatura de raciocínio do Gemini) viaja em `LlmContent.ToolCall.providerState`.
- **Ferramentas por binding.** `ToolBinding` (`Inline`/`Http`/`Process`) + `BindingExecutor`. Toda chamada gera
  `AGENT_TOOL_INVOKED`. É a fundação para o futuro `AD_HOC_SUBPROCESS`: um binding novo, sem mexer no loop.
- **Portas antes de infraestrutura.** Módulos de domínio não dependem de Spring nem do motor; só o starter, o
  `agents-app` e `agents-dms-client` usam Spring. Persistência (no starter): conversas,
  transcrição e lock em `MongoConversationStore`, anexos em `GridFsAttachmentStore`, catálogo em
  `MongoAgentDefinitionStore`; `kikwi-agents.storage.store=memory` para testes. Nada em disco local.
- **Tenant em tudo.** Todo registro carrega `tenantId`; canal de um tenant nunca atende outro.

## Módulos de montagem

- `agents-spring-boot-starter` (`io.kikwiflow.agents.platform`): a autoconfiguração da plataforma (catálogo, worker,
  processos como ferramenta, conversas, API do catálogo). Hosts substituem por bean: `OutboundGateway` (saída para os
  canais), `LlmClientResolver` (credencial de LLM por tenant), `AttachmentStore`, `AgentDefinitionStore`,
  `InboundPreprocessor` (trabalho único sobre cada mensagem recebida, fora do lock da conversa — ex.: transcrever áudio;
  o anexo fica e a transcrição entra como texto).
- **Omnicanal.** A conversa é do lead (`Conversation.channel` = por onde ele fala agora). Entrada:
  `ConversationService.onInbound(InboundMessage)` com o lead já identificado pelo roteador. Lead escrevendo por um
  canal novo com conversa aberta em outro (até 7 dias) recebe a pergunta "continuar ou outro assunto?"
  (`RouteDecision.AskChannelSwitch`, `PendingChannelSwitch`); continuar troca o canal e registra a troca na transcrição.
  `linkLeads` junta dois leads quando o roteador descobre que são a mesma pessoa.
- `agents-app`: só a demonstração (tenant/canal de demo, agentes de exemplo, dublês). O produto multi-tenant fica em
  `../kikwi-agents-platform` e consome o starter.

## Ponte com o motor (Kikwiflow 0.1.80+)

E1–E4 do doc 31 existem no motor. A ponte:
- `EngineMissionSource` (starter) implementa `MissionSource` sobre a API de worker do `KikwiflowEngine`
  embutido, com `IdentityContext.system()`. Traduz 409/404 em `MissionLeaseLostException` e 422 em
  `MissionOutputRejectedException`.
- `MissionWorker` (`agents-runtime`) adquire missões, liga cada uma à conversa do seu `businessKey`, roda os turnos
  e chama complete/fail/bpmn-error. Missões em andamento ficam só em memória: se o app cair, o lease vence e a
  missão é readquirida. `MissionScheduler` agenda aquisição e renovação.
- Handoff = complete com `resultado = HUMANO`; opt-out = erro de negócio `LEAD_OPT_OUT`; limites (`maxTurns`,
  `ttl`) estourados = handoff.
- `processes/*.kikwi` entram no classpath pelo build do `agents-app` e o auto-deploy está ligado. Os executores
  `criarNegocioCrm`/`registrarPerda` são dublês (`kikwi-agents.sample-stubs.enabled`) até existir a integração real do cliente.
- Agentes vêm do **catálogo** (`AgentCatalog` + `AgentDefinitionStore`, Mongo `agent_definitions`), no formato do
  `.agent.json` do Agent Studio; API em `/api/tenants/{tenantId}/agents`. Ferramenta `PROCESS` = processo do motor
  (`EngineProcessTools`): o modelo recebe só o retorno do processo (`ProcessContract`). Ver `docs/03`.
- **Nada de cliente ou produto aqui:** isto é infraestrutura do Kikwiflow. Casos concretos ficam em `../cases/`.
- Teste ponta a ponta sem Mongo: `PreVendasMissionFlowTest`, sobre `EmbeddedEngine` (motor real em memória).
- Trabalhando junto com mudanças não publicadas do motor: `mvn install -Dgpg.skip -Dmaven.javadoc.skip=true
  -Dmaven.source.skip=true` em `../atoxfy` e aponte `kikwiflow.version` para a versão local.

## Convenções

- Header Apache 2.0 (copyright Atoxfy) em `.java` e `pom.xml`, igual ao motor.
- Records imutáveis para modelo; `with*` para cópias.
- Javadoc em português explicando o **porquê**, na densidade do código existente.
