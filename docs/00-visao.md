# 00 — Visão

## O problema

Empresas com funil comercial dependem de profissionais de **pré-venda**: atender o lead rápido, entender o que ele
quer, coletar informações e fotos e convencê-lo a dar o próximo passo (no caso de compra de veículos, **agendar uma visita** à loja
para avaliar e vender o carro). É um trabalho repetitivo, sensível a tempo de resposta (lead que espera esfria) e
caro de escalar.

Agentes de IA conseguem fazer boa parte desse trabalho, mas, soltos, viram uma caixa-preta:
- ninguém sabe por que o agente desistiu de um lead;
- não há como impor regras de negócio;
- não há como trocar de modelo sem medo;
- o gestor não tem auditoria.

## A proposta

Uma plataforma em que **cada tenant**:
1. configura **canais** (site no MVP; WhatsApp e e-mail depois);
2. configura **agentes** versionados (persona, instruções, ferramentas, modelo, limites);
3. desenha o **processo comercial** no Kikwiflow Craft, decidindo onde o agente auxilia, onde assume e para onde
   escala;
4. acompanha tudo no **Kikwiflow Monitor**: que missão cada agente recebeu, o que fez e o que entregou.

## Pilares (herdados do Kikwiflow)

- **Flexibilidade.** O processo é JSON versionado, desenhado por tenant, sem linguagem de expressão: decisões em
  Java ou por variável.
- **Escalabilidade.** Missões duráveis com lease; workers horizontais; conversas com single-writer.
- **Auditoria.** Contrato de saída validado pelo motor, trilha `AGENT_*` no outbox, transcrição por referência.

## Fora de escopo agora

- Construir um modelador de agentes visual. A configuração começa em JSON/API; a UI vem depois.
- O modelo ad-hoc à la Camunda, em que o agente escolhe atividades do processo. A fundação já fica pronta para ele
  (`docs/03-agentes-e-ferramentas.md`).
