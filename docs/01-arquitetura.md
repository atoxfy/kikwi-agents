# 01 — Arquitetura

```
 Site / WhatsApp / ...
          │  (no HOST: webhook, SSE — roteador de canais, identidade do lead)
          ▼
 ┌──────────────────────────┐  InboundMessage   ┌───────────────────────────┐
 │ roteador do host         │ ────────────────► │ agents-conversation       │
 │ OutboundGateway.deliver  │ ◄──────────────── │ conversa do lead, inbox,  │
 └──────────────────────────┘  OutboundMessage  │ transcrição, lock, rota,  │
                                                │ troca de canal            │
                                                └────────────┬──────────────┘
                                                             │ RouteDecision
                         ┌──────────────────────────┬────────┴─────────────┬──────────────────┐
                         ▼                          ▼                      ▼                  ▼
               StartProcess (1ª msg)     DeliverToMission       AskChannelSwitch         HumanInbox
                         │                          │
                         ▼                          ▼
 ┌────────────────────────────────────┐        ┌──────────────────────────────────────┐
 │ Kikwiflow (embutido no host)       │◄──────►│ agents-runtime                        │
 │ processo, AGENT_TASK, timers,      │ lease  │ MissionSource (fetch/complete/report) │
 │ gateways, outbox/auditoria         │        │ AgentTurnProcessor ─► LlmClientResolver│
 └────────────────────────────────────┘        │ ferramentas: processos Kikwiflow      │
                                               └──────────────────────────────────────┘
```

## Ciclo de vida de uma conversa

1. **Primeira mensagem.**
   - O roteador do host identifica o lead e entrega a mensagem (`InboundMessage`); o `InboundPreprocessor` roda antes
     (ex.: transcreve áudio).
   - O `ConversationService` cria contato e conversa e grava a mensagem. Se o lead tem conversa aberta em outro canal,
     pergunta antes se ele quer continuar (`AskChannelSwitch`).
   - Decide `StartProcess`: a instância nasce com `businessKey = conversationId`, o tenant do canal e as variáveis
     de entrada.
2. **O processo chega num `AGENT_TASK`.** O motor cria a missão (wait state).
3. **Aquisição.**
   - O worker de missões do runtime adquire a missão (fetch-and-lock com lease).
   - Liga `conversation.activeMissionTaskId`.
   - Reporta `AGENT_MISSION_STARTED`: versão do agente, modelo, hash do prompt.
4. **Cada mensagem do lead.**
   - `DeliverToMission`: o `AgentTurnProcessor` monta o pedido (persona + instruções + objetivo renderizado +
     transcrição).
   - Executa as ferramentas pedidas, auditando cada uma (`AGENT_TOOL_INVOKED`).
   - Devolve `Reply`, entregue pelo `OutboundGateway` no canal atual da conversa. O lease é renovado.
5. **Fim da missão.** Uma de quatro saídas:
   - `Completed`: o runtime chama `complete` com o contrato. O motor valida; se inválido, responde 422 e o agente
     continua.
   - `HandedOff`: a conversa vai para `HANDED_OFF` e a missão conclui com `resultado = HUMANO`.
   - `Exhausted`: falha técnica, com retry e depois incidente no Monitor.
   - Boundary do processo (timer de 24h, "humano assumiu"): cancela a missão. O próximo `extendLock` recebe 404 e o
     runtime solta a conversa.
6. **O processo segue.** Gateway por `resultado`, depois recuperação (outra missão, outro agente), consultor humano
   ou criação do negócio no CRM.

## Decisões

| Decisão | Por quê |
|---|---|
| Missão, não turno | O processo fica legível para o negócio e a latência da conversa não depende do ciclo de aquisição do motor. |
| Runtime no mesmo app do motor (MVP) | Um deploy e uma transação de leitura mais simples. `MissionSource` é porta: o runtime pode virar serviço separado via REST sem mudar o loop. |
| Inbox na plataforma | Compensa a falta de correlação com buffer no motor (E5). O lead nunca "fala com o vazio". |
| Single-writer por conversa | Duas mensagens seguidas não geram duas respostas cruzadas. Em cluster: lock distribuído. |
| Sem SDK de LLM | HTTP + Jackson mantém o controle do formato e evita conflito de Jackson 2/3 (o mesmo problema do MCP no motor). |
| Claim check | Variáveis pequenas, sem PII bruta no outbox; anexos ficam no GridFS/DMS e a conversa guarda só a referência. |
| Canais no host | Cada produto tem a própria identidade de lead e os próprios canais; a plataforma só conhece "o lead X falou pelo canal Y". |

## Persistência

Com o starter, tudo no Mongo do host (o mesmo banco do motor):
- `agents_contacts`, `agents_conversations`, `agents_messages`, `agents_channel_switches`, `agents_locks` (lock
  distribuído por lead);
- anexos no GridFS `agents_attachments` (ou num DMS externo);
- `agent_definitions`: catálogo de agentes, versões imutáveis por tenant.

`kikwi-agents.storage.store=memory` troca tudo por memória (testes).
