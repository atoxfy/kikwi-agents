# 05 — Roadmap

## Fase 0 — Fundação (feito, 2026-10-25)

- Spec do motor: `../atoxfy/docs/engine/31-orquestracao-agentica.md` (E1–E8).
- Modelo, porta de LLM (Anthropic + fake), conversa com roteamento, turno do agente com ferramentas e auditoria,
  cliente DMS, app com motor embutido.
- Processo `pre-vendas` (rascunho na época) e playbook.

## Fase 1 — Motor (no repositório `atoxfy`) — itens 1–4 feitos no 0.1.79 (2026-10-25)

1. **E2:** fetch-and-lock com lease, `extend-lock`, `failure`, `bpmn-error`.
2. **E1:** nó `AGENT_TASK` (modelo, parser, deploy KIKWI-067..070, testes no `kikwi-core-tests`).
3. **E3:** eventos `AGENT_*` + `EVENT_CORRELATED` + `POST /external-tasks/{id}/activities`; linha do tempo no
   histórico.
4. **E4:** formato `FILE` (validator + KIKWI-071) com paridade em forms-core e lint no Craft.
5. **Craft/Monitor:** ~~nó `AGENT_TASK` no modelador~~ (feito em 2026-10-25, com lint `KIKWI-028`…`031`); filtro
   "Missões de agente" e atividades no Monitor.

## Fase 2 — MVP de pré-vendas no site

- ~~`MissionSource` in-process + worker de missões (lease, renovação, complete/fail, 404 em cancelamento).~~
  Feito (2026-10-25): `EngineMissionSource` + `MissionWorker` + `MissionScheduler`.
- ~~Ligar `DeliverToMission` → `AgentTurnProcessor` → saída do canal.~~ Feito, com teste ponta a ponta
  (`PreVendasMissionFlowTest`).
- ~~Repositórios Mongo (conversas, mensagens, catálogo de agentes) e anexos no GridFS.~~ Feito (2026-11-01).
- ~~Catálogo de agentes versionado + processos como ferramenta (Agent Studio no Craft).~~ Feito (2026-11-01).
- ~~Canais no host com conversa omnicanal e troca de canal; WhatsApp e site no produto.~~ Feito (2026-11-02).
- ~~Multi-provedor (Anthropic, Gemini) com credencial por tenant; transcrição de áudio no host.~~ Feito (2026-11-02).
- Ferramentas: `solicitar_midia`, `consultar_fipe`, `consultar_horarios`/`agendar_visita` (API de agenda
  do cliente), executor `criarNegocioCrm` (CRM do cliente) e `registrarPerda`.
- Perfis `pre-vendas` e `recuperacao`; avaliação com conversas de referência antes de ligar.
- DMS: service account com papel de escrita dedicado (hoje o upload exige `ROLE_ADMIN`).
- Painel mínimo do consultor: ver conversa, "assumir" (correlaciona `humano-assumiu:<conversationId>`), responder.
- Piloto em uma franquia, com o processo humano atual em paralelo.

## Fase 3 — Plataforma

- **E5** (correlação com buffer / `correlateOrStart`), **E6** (definições por tenant), **E7** (cancelamento).
- Templates do WhatsApp (fora da janela de 24h) → recuperação real.
- Resposta em voz (TTS); provedor Bedrock.
- E-mail.
- Bindings `Http` e `Process`.
- Custo por missão, guardrails avançados (PII, moderação, injeção de prompt).

## Fase 4 — Caminho elegante

- `AD_HOC_SUBPROCESS` no motor + `BindingExecutor<Activity>`: o agente escolhe atividades do processo, cada uma como
  token auditável.
- Event sub-process (ex.: "lead pediu humano" de qualquer ponto).
