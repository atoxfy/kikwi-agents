# 02 — Canais

O kikwi-agents não implementa canais. O **host** (o produto que usa o starter) tem um roteador de canais que:
- recebe as mensagens de cada canal (webhook do WhatsApp, API do site...) e descobre **quem é o lead**;
- entrega ao `ConversationService.onInbound(InboundMessage)`;
- implementa o `OutboundGateway`, que leva as respostas ao lead pelo canal da conversa.

Exemplo completo: `../kikwi-agents-platform` (pacote `channels`: site e WhatsApp Cloud API).

## Contrato (`agents-channels-api`)

| Tipo | O quê |
|---|---|
| `InboundMessage(tenantId, leadId, displayName, ChannelContext, parts, receivedAt, channelMessageId)` | uma fala do lead; `channelMessageId` deduplica reentregas |
| `ChannelContext(ChannelRef, label, ChannelEntry, ConsentPolicy)` | por onde ele fala, qual processo uma conversa nova inicia e as palavras de opt-out |
| `OutboundGateway.deliver(tenantId, leadId, ChannelRef, conversationId, OutboundMessage)` | saída; `OutboundMessage` tem partes, template e respostas sugeridas (botões) |
| `InboundPreprocessor` | trabalho único sobre cada mensagem antes de entrar na conversa, fora do lock (ex.: transcrever áudio) |
| `AttachmentStore` | onde ficam os anexos; a conversa guarda só a referência |

## Omnicanal

A conversa é **do lead**; `Conversation.channel` é por onde ele fala agora. Quando o lead escreve por um canal novo
tendo conversa aberta em outro (até 7 dias), a plataforma pergunta, pelo canal novo, se ele quer continuar ou se é
outro assunto (`RouteDecision.AskChannelSwitch`, `PendingChannelSwitch`):
- **continuar**: a conversa passa para o canal novo e a troca fica registrada na transcrição;
- **outro assunto**: conversa nova (e processo novo); a anterior continua aberta;
- resposta que não dá para entender: pergunta de novo uma vez e depois segue como outro assunto.

Quando o host descobre que dois leads são a mesma pessoa (ex.: o visitante do site informou o telefone que já fala
pelo WhatsApp), chama `ConversationService.linkLeads`.

## Requisitos por canal (para o host)

- **WhatsApp:** janela de 24h (fora dela, só template), mídia baixada pela Graph API, botões interativos (até 3),
  assinatura `X-Hub-Signature-256`, reentregas da Meta.
- **Site:** sessão por navegador, SSE (o lead pode estar desconectado — a resposta fica na transcrição e o site busca o
  histórico ao reabrir).
- **E-mail** (futuro): threads, anexos grandes.
