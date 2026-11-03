# kikwi-agents

Plataforma de **agentes conversacionais multi-tenant** orquestrada e auditada pelo
[Kikwiflow](../atoxfy). Cada empresa desenha o próprio processo comercial e decide onde um agente de IA **auxilia** ou
**assume** o trabalho (pré-venda, recuperação, atendimento), com escalação entre agentes e para humanos.

Primeiro caso: substituir o **pré-vendas** de uma empresa que compra carros de particulares (qualificar quem quer vender o carro e agendar a visita na
agência).

## Por que Kikwiflow

O agente conversa; o **processo decide**. Escalar para recuperação, passar para um consultor ou desistir são rotas
modeladas, versionadas e auditáveis, nunca texto livre do LLM. O motor valida o contrato de saída de cada missão
(o motor não confia no LLM) e registra, para cada missão:
- qual agente e qual versão atuou;
- com qual modelo e qual hash de prompt;
- que ferramentas usou;
- o que entregou.

## Estrutura

| Módulo | O que é |
|---|---|
| `agents-model` | Domínio puro: agentes, canais (referência), conversas, ferramentas, missões |
| `agents-llm-api` | Porta agnóstica de LLM (`LlmClient`, `LlmRouter` por `provider:model`) |
| `agents-llm-anthropic` | Adapter da Messages API da Anthropic (HTTP + Jackson, sem SDK) |
| `agents-llm-gemini` | Adapter da API Gemini do Google (HTTP + Jackson, sem SDK) |
| `agents-llm-fake` | LLM roteirizado para testes e demo sem chave |
| `agents-channels-api` | Contrato com o roteador de canais do host (`InboundMessage`, `OutboundGateway`, `InboundPreprocessor`, `AttachmentStore`) |
| `agents-conversation` | Conversa omnicanal do lead, transcrição, single-writer, troca de canal, roteamento |
| `agents-runtime` | Catálogo de agentes, loop agente↔LLM, ferramentas, prompt em camadas, auditoria, worker de missões |
| `agents-dms-client` | Anexos num DMS externo (`dms://id/versão`, claim check), alternativa ao GridFS |
| `agents-spring-boot-starter` | Tudo montado para um host Spring Boot com o motor embutido: Mongo, GridFS, catálogo REST, worker |
| `agents-app` | Demonstração local (tenant de demo, agentes de exemplo, dublês) |
| `processes/` | Processo de exemplo usado pela demonstração e pelos testes |

Canais (site, WhatsApp) e o cadastro de tenants ficam no produto que usa o starter — ver
`../kikwi-agents-platform`.

## Usando

```xml
<dependency>
  <groupId>io.kikwiflow.agents</groupId>
  <artifactId>agents-spring-boot-starter</artifactId>
  <version>0.1.2</version>
</dependency>
```

O host fornece o `OutboundGateway` (saída para os canais) e chama `ConversationService.onInbound(...)` com o lead já
identificado; opcionalmente substitui `LlmClientResolver` (credencial por tenant), `InboundPreprocessor`
(ex.: transcrição de áudio) e `AttachmentStore`.

## Rodando

```bash
mvn test                                         # sem rede; os testes de Mongo pulam se não houver Mongo local
ANTHROPIC_API_KEY=... mvn -pl agents-app spring-boot:run    # demo, porta 8090 (ou GEMINI_API_KEY=...)
```

Requer JDK 21, Maven 3.9 e o Kikwiflow 0.1.80+ (Maven Central).

Mais em `docs/` e em `docs/05-roadmap.md`.
