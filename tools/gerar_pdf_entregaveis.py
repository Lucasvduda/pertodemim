"""Gera o PDF de entregáveis (solução, arquitetura e evidências das 4 áreas) em entrega/Entregaveis_PertoDeMim.pdf.

    python tools/gerar_pdf_entregaveis.py

Lê os resultados reais já gravados no repositório (avaliações, simulação, verificação de testes, prints em
area4_devops/evidencias/prints). Nada aqui é inventado: o que ainda precisa ser preenchido pelo grupo aparece
em caixas amarelas "PREENCHER". Requer: pip install markdown, e o Microsoft Edge ou o Google Chrome.
"""
from __future__ import annotations

import base64
import html
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import markdown

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
from md_para_pdf import achar_navegador  # noqa: E402

PRINTS = RAIZ / "area4_devops" / "evidencias" / "prints"
ESPECIALIDADES = ["Clínico geral", "Médico de família e comunidade", "Pediatria", "Ginecologia e obstetrícia",
                  "Ortopedia e traumatologia", "Cardiologia", "Oftalmologia", "Otorrinolaringologia",
                  "Dermatologia", "Psiquiatria", "Urologia", "Neurologia"]

CSS = """
@page{size:A4;margin:14mm 12mm}
body{font-family:Segoe UI,Arial,sans-serif;color:#14202e;line-height:1.45;font-size:11.5px}
h1{color:#0b4f8a;font-size:26px;margin:0 0 4px}h2{color:#0b4f8a;border-bottom:2px solid #0b4f8a;padding-bottom:3px;font-size:18px;margin-top:22px;page-break-after:avoid}
h3{font-size:13.5px;color:#0b4f8a;margin:14px 0 4px;page-break-after:avoid}
table{border-collapse:collapse;width:100%;margin:8px 0;font-size:10.5px}th,td{border:1px solid #c9d4e0;padding:4px 6px;text-align:left;vertical-align:top}
th{background:#eef3f9}code{background:#eef3f9;padding:1px 4px;border-radius:3px;font-size:10.5px}
pre{background:#eef3f9;padding:8px;white-space:pre-wrap;word-break:break-word;font-size:10px;border-radius:4px}
img{max-width:100%;border:1px solid #c9d4e0;border-radius:4px}
.par{display:flex;gap:8px;align-items:flex-start}.par>div{flex:1;min-width:0}
figure{margin:6px 0;page-break-inside:avoid}figcaption{font-size:10px;color:#51606f;margin-top:2px}
.quebra{page-break-before:always}
.preencher{background:#fff4c2;border:2px dashed #b58900;padding:8px 10px;margin:8px 0;border-radius:4px;page-break-inside:avoid}
.aviso{background:#eef3f9;border-left:4px solid #0b4f8a;padding:6px 10px;margin:8px 0}
.capa{padding:30px 0 10px}.sub{color:#51606f;font-size:13px}
svg text{font-family:Segoe UI,Arial,sans-serif}
"""


def img(nome: str, legenda: str, largura: str = "100%") -> str:
    arq = PRINTS / nome
    if not arq.exists():
        return f"<div class='preencher'>FALTA a imagem {html.escape(nome)} (rode os passos de captura).</div>"
    b64 = base64.b64encode(arq.read_bytes()).decode()
    return f"<figure><img style='width:{largura}' src='data:image/png;base64,{b64}'><figcaption>{html.escape(legenda)}</figcaption></figure>"


def md(texto: str) -> str:
    return markdown.markdown(texto, extensions=["tables", "fenced_code"])


def ler(rel: str) -> str:
    return (RAIZ / rel).read_text(encoding="utf-8")


def prompt_do_sistema() -> str:
    """Extrai o prompt de PromptTriagem.sistema() do código-fonte (o PDF mostra exatamente o que a API envia)."""
    fonte = ler("area3_mvp/api/src/main/java/br/com/pertodemim/triagem/PromptTriagem.java")
    corpo = fonte.split("public static String sistema()")[1].split("return ", 1)[1].split(";", 1)[0]
    partes = re.findall(r'"((?:[^"\\]|\\.)*)"', corpo)
    texto = "".join(partes).replace('\\"', '"')
    return texto.replace("LISTA: []", "LISTA: [" + ", ".join(ESPECIALIDADES) + "]")


def diagrama() -> str:
    def caixa(x, y, w, h, titulo, linhas=(), cor="#eaf2fb", borda="#0b4f8a"):
        t = f"<rect x='{x}' y='{y}' width='{w}' height='{h}' rx='8' fill='{cor}' stroke='{borda}' stroke-width='1.5'/>"
        t += f"<text x='{x + w / 2}' y='{y + 18}' text-anchor='middle' font-size='12' font-weight='700' fill='#0b4f8a'>{html.escape(titulo)}</text>"
        for i, l in enumerate(linhas):
            t += f"<text x='{x + w / 2}' y='{y + 35 + i * 14}' text-anchor='middle' font-size='10' fill='#14202e'>{html.escape(l)}</text>"
        return t

    def seta(x1, y1, x2, y2, rot=""):
        t = f"<line x1='{x1}' y1='{y1}' x2='{x2}' y2='{y2}' stroke='#51606f' stroke-width='1.6' marker-end='url(#p)'/>"
        if rot:
            t += f"<text x='{(x1 + x2) / 2}' y='{(y1 + y2) / 2 - 4}' text-anchor='middle' font-size='9' fill='#51606f'>{html.escape(rot)}</text>"
        return t

    s = ["<svg viewBox='0 0 940 430' xmlns='http://www.w3.org/2000/svg' style='width:100%;border:1px solid #c9d4e0;border-radius:6px;background:#fff'>",
         "<defs><marker id='p' markerWidth='8' markerHeight='8' refX='7' refY='4' orient='auto'><path d='M0,0 L8,4 L0,8 z' fill='#51606f'/></marker></defs>"]
    s.append(caixa(10, 30, 150, 60, "Pessoa (web)", ["front HTML/JS", "triagem, unidades,", "agendamento"], "#fff4e5", "#b25e00"))
    s.append(caixa(10, 150, 150, 60, "Simulador IoT (Python)", ["fila, presença,", "heartbeat (sem hardware)"], "#fff4e5", "#b25e00"))
    s.append(caixa(230, 20, 330, 250, "API Spring Boot 3.5 · Java 17", [], "#eaf2fb"))
    s.append(caixa(245, 55, 140, 60, "Triagem", ["red flags → regras/LLM", "→ validação"], "#ffffff"))
    s.append(caixa(400, 55, 145, 60, "Ranking", ["online + profissional", "+ distância + espera"], "#ffffff"))
    s.append(caixa(245, 130, 140, 60, "Agendamento", ["horários, protocolo"], "#ffffff"))
    s.append(caixa(400, 130, 145, 60, "Ingestão IoT", ["X-Device-Key, idempotente"], "#ffffff"))
    s.append(caixa(245, 205, 300, 50, "Segurança", ["JWT + BCrypt · rate limit · validação · CSP"], "#ffffff"))
    s.append(caixa(250, 310, 150, 60, "LLM (opcional)", ["OpenAI/Groq/Gemini/Ollama", "com fallback por regras"], "#f3f0ff", "#5b3fb0"))
    s.append(caixa(430, 310, 130, 60, "Banco", ["H2 em memória", "ou PostgreSQL"], "#ffffff"))
    s.append(caixa(620, 30, 130, 70, "Bronze", ["JSONL por dia", "origem: api / exemplo"], "#e8f6ee", "#1d7a46"))
    s.append(caixa(620, 140, 130, 60, "ETL (pandas)", ["valida, deduplica,", "idempotente"], "#e8f6ee", "#1d7a46"))
    s.append(caixa(770, 140, 160, 60, "Silver → Gold (CSV)", ["6 tabelas gold"], "#e8f6ee", "#1d7a46"))
    s.append(caixa(770, 240, 160, 70, "Dashboard Streamlit", ["5 indicadores", "filtros + funções analíticas"], "#e8f6ee", "#1d7a46"))
    s.append(caixa(620, 340, 310, 70, "DevOps", ["Git · Docker (multi-stage) + compose · GitHub Actions", "testes automatizados + varredura de segredos"], "#fdecea", "#b3261e"))
    s.append(seta(160, 60, 230, 60, "HTTPS/JSON"))
    s.append(seta(160, 180, 230, 160, "POST /api/iot/eventos"))
    s.append(seta(325, 270, 325, 310))
    s.append(seta(495, 270, 495, 310))
    s.append(seta(560, 70, 620, 65, "grava"))
    s.append(seta(685, 100, 685, 140))
    s.append(seta(750, 170, 770, 170))
    s.append(seta(850, 200, 850, 240))
    s.append("</svg>")
    return "".join(s)


def verificacao() -> str:
    arq = RAIZ / "area4_devops" / "evidencias" / "verificacao.md"
    return md(arq.read_text(encoding="utf-8")) if arq.exists() else "<div class='preencher'>Rode python area4_devops/verificar_tudo.py</div>"


def resultado(nome: str) -> str:
    arq = RAIZ / "area1_ia" / "resultados" / nome
    return f"<pre>{html.escape(arq.read_text(encoding='utf-8'))}</pre>" if arq.exists() else "<div class='preencher'>Falta " + nome + "</div>"


def montar() -> str:
    simul = md(ler("area1_ia/resultados/simulacao_impacto.md"))
    plano = md(ler("area4_devops/PLANO_DE_TESTES.md"))
    seguranca = md(ler("area4_devops/SEGURANCA.md"))
    ci = html.escape(ler(".github/workflows/ci.yml"))
    dockerfile = html.escape(ler("area3_mvp/api/Dockerfile"))
    compose = html.escape(ler("docker-compose.yml"))
    sondagem = html.escape(ler("area4_devops/evidencias/sondagem_api.txt"))

    p = []
    p.append(f"""
<div class='capa'><h1>PertoDeMim</h1>
<div class='sub'>Global Solution 2026/2 · CitizenTech · FIAP · 4ESOA<br>Entregáveis das 4 áreas: solução, arquitetura e evidências</div></div>
<div class='preencher'><b>PREENCHER PELO GRUPO:</b> nome do grupo, RM e nome de cada integrante, link público do vídeo pitch.<br>
Grupo: ________ &nbsp; Integrantes (nome e RM): ________ &nbsp; Pitch: ________</div>
<div class='aviso'><b>Dados simulados.</b> As unidades e coordenadas são reais (CNES, 51 unidades de São Paulo). Fila, presença de profissional,
conexão dos sensores, demanda, catálogo de especialidades por unidade e números de impacto são <b>simulados</b>. Nenhum número deste documento é medição real.</div>

<h2>1. Solução</h2>
<h3>Cidadão e problema</h3>
<p>Moradores de comunidades carentes perdem tempo e dinheiro indo à unidade pública errada: a mais próxima muitas vezes não tem o profissional
de que precisam naquele momento. Quem quebra a perna precisa de um ortopedista, e ir à unidade mais perto que não tem ortopedista significa
voltar e tentar de novo (ODS 3, saúde e bem-estar, e ODS 10, redução das desigualdades).</p>
<h3>Como funciona</h3>
<ol><li>A pessoa descreve o que sente ou precisa (saúde, documento ou assistência social). Sinais de emergência levam direto ao 192 (188 em ideação suicida).</li>
<li>A triagem indica a especialidade e a urgência (escala de Manchester) e o texto é anonimizado antes de ser guardado.</li>
<li>O ranking mostra as unidades que estão online e <b>têm o profissional agora</b>, não só as mais próximas, e avisa quando a mais próxima não atende.</li>
<li>A pessoa agenda, e cada evento alimenta a camada de dados que gera os indicadores do dashboard.</li></ol>
<h3>Ecossistema, tecnologias e impacto</h3>
<p><b>Quem participa:</b> cidadão, unidades de saúde (informam fila e presença pelos sensores), gestores (dashboard) e assistência social.
<b>Tecnologias:</b> Java 17 e Spring Boot (API), HTML/JS (front), Python (ETL, dashboard Streamlit e simulador IoT), pandas, Docker, GitHub Actions, JUnit e pytest.
<b>Impacto:</b> viagens perdidas e km evitados, medidos por simulação com premissas declaradas (seção 3.1) e pelo dashboard (indicador 4).
<b>ODS:</b> 3 e 10.</p>

<h2 class='quebra'>2. Arquitetura geral</h2>
{diagrama()}
<p>As áreas se integram: a API grava cada evento IoT, triagem e agendamento no bronze; o ETL transforma em gold; o dashboard lê o gold. A IA usa o ranking
alimentado pelos sensores, e o dashboard mostra o resultado dos sensores e das triagens.</p>

<h2 class='quebra'>3. Área 1: IA e automação</h2>
<h3>3.1 IA aplicada</h3>
<p>Triagem em 3 camadas: (1) detector de sinais de emergência por regras fixas, que nunca chama o LLM; (2) classificador por regras (padrão, sem internet) ou LLM
compatível com a API da OpenAI; (3) validação da saída: lista fechada de 12 especialidades, texto de orientação vindo do app, piso de urgência (o LLM não rebaixa laranja ou pior)
e fallback automático para as regras em qualquer falha. Especialidades: {", ".join(ESPECIALIDADES)}.</p>
<h3>3.2 Prompts, regras e automação</h3>
<p>Prompt de sistema enviado ao LLM (extraído do código; o texto do cidadão vai entre <code>&lt;&lt;&lt;</code> e <code>&gt;&gt;&gt;</code> e é tratado só como dado, contra prompt injection):</p>
<pre>{html.escape(prompt_do_sistema())}</pre>
<p>Resposta obrigatória em JSON (schema estrito): <code>servico</code> (saude, documento, assistencia_social), <code>especialidade</code> (uma das 12 ou "nenhuma"),
<code>urgencia</code> (vermelho, laranja, amarelo, verde, azul), <code>orientacao</code> e <code>perguntaAdicional</code>.
Regras de automação: ranking por <code>score = 0,5·distância/distMax + 0,3·espera/esperaMax + 0,2·penalidade(latência/2000)</code>; elegível = online (heartbeat &lt; 2 min)
e oferece o serviço e (profissional presente com check-in &lt; 4 h ou agenda aberta). Tempos-alvo Manchester: vermelho 0, laranja 10, amarelo 60, verde 120, azul 240 min.</p>
<h3>3.3 Demonstração (entrada e saída)</h3>
<div class='par'><div>{img('tela3_resultado.png', 'Entrada: "quebrei a perna jogando bola". Saída: Ortopedia e traumatologia, muito urgente, e aviso de que a unidade mais próxima (0,6 km) não tem o atendimento.')}</div>
<div>{img('tela9_emergencia.png', 'Entrada: "estou com dor forte no peito e falta de ar". Saída: emergência, ligar 192, sem passar pelo LLM nem pelo ranking.')}</div></div>
{img('tela4_ranking.png', 'Ranking: a unidade indicada (5,75 km) tem o ortopedista presente agora, com espera estimada.', '70%')}
<h3>3.4 Lógica e ganho (métricas)</h3>
<p><b>Avaliação da triagem</b> (script <code>area1_ia/avaliar_triagem.py</code>, contra a API em execução). Conjunto ouro (40 frases; as regras foram ajustadas conhecendo essas frases, o resultado é otimista):</p>
{resultado('conjunto_ouro_regras.txt')}
<p>Primeira execução, antes do ajuste:</p>
<pre>{html.escape(ler('area1_ia/resultados/conjunto_ouro_regras_1a_execucao.txt').splitlines()[0])}</pre>
<p><b>Conjunto de validação</b> (40 frases novas, escritas depois do ajuste; <code>area1_ia/frases_validacao.csv</code>). A primeira execução achou duas falhas de segurança, depois corrigidas e cobertas por teste:</p>
{resultado('conjunto_validacao_1a_execucao_antes_das_correcoes.txt')}
<p>Resultado depois das correções (regras): serviço 100%, especialidade 80,8% (21/26), urgência exata 80,0%, urgência ±1 nível 97,5%, recall do vermelho 100% (6/6), 0 falsos vermelhos.
Esse conjunto deixou de ser independente depois das correções.</p>
<div class='preencher'><b>PREENCHER (quando houver chave válida):</b> resultado com LLM real (<code>python area1_ia/avaliar_triagem.py --rotulo openai</code>, ou groq/gemini/ollama) e comparação com as regras.
Até lá, o ganho medido é o das regras; nenhuma chamada real a LLM foi concluída com sucesso.</div>
<p><b>Antes e depois (impacto no cidadão), por simulação:</b></p>
{simul}

<h2 class='quebra'>4. Área 2: Big Data e visualização</h2>
<h3>4.1 Estrutura dos dados</h3>
<table><tr><th>Camada</th><th>Conteúdo</th><th>Regra</th></tr>
<tr><td>bronze</td><td>JSONL por dataset (<code>iot</code>, <code>triagens</code>, <code>agendamentos</code>) e dia; campo <code>origem</code> = api ou exemplo</td><td>só acrescenta</td></tr>
<tr><td>silver</td><td>CSV validado e deduplicado por <code>origem|id</code>, horário de São Paulo; <code>rejeitados.csv</code> com o motivo</td><td>recalculado inteiro, idempotente</td></tr>
<tr><td>gold</td><td><code>g_espera</code>, <code>g_cobertura_especialidade</code>, <code>g_demanda</code>, <code>g_impacto</code>, <code>g_conectividade</code>, <code>g_pressao</code></td><td>tabelas prontas para o dashboard</td></tr></table>
<p>Dimensões reais (CNES): <code>dim_unidades</code> (51 unidades), <code>dim_especialidades</code> e <code>dim_servicos</code>.</p>
<h3>4.2 Ingestão e processamento</h3>
<p>Caminho dos dados: sensor simulado ou pessoa usando o app → API → bronze (JSONL) → <code>etl.py</code> → silver → gold. Execução real (zip limpo, seguindo o README):
<code>iot: lidos 67893, aceitos 67893, duplicados 0, rejeitados 0 · triagens: lidos 3683 · agendamentos: lidos 1025</code>, mais os eventos gravados pela API (origem <code>api</code>).
O dashboard mostra a proporção exemplo x api na barra lateral.</p>
<h3>4.3 Dashboard (5 indicadores, com filtros de região, especialidade, período e urgência)</h3>
{img('dash1_espera.png', 'Indicador 1: espera média e p90 por hora do dia.')}
{img('dash2_cobertura.png', 'Indicador 2: cobertura de especialidade por unidade.')}
{img('dash3_demanda.png', 'Indicador 3: demanda, com média móvel de 7 dias e variação semanal (só semanas completas).')}
{img('dash4_impacto.png', 'Indicador 4: idas evitadas, km evitados e custo evitado (simulação).')}
{img('dash5_disponibilidade.png', 'Indicador 5: disponibilidade dos sensores, latência p90 e índice de pressão das unidades.')}
{img('dash6_filtro_ortopedia.png', 'Filtro de especialidade aplicado (Ortopedia e traumatologia): os indicadores e gráficos mudam com o filtro.')}
<h3>4.4 Funções analíticas</h3>
<p>Média móvel de 7 dias, variação semanal (apenas semanas completas), percentil 90 da espera e da latência, cobertura = presente ÷ horas observadas, índice de pressão = (indicações + agendamentos) ÷ (profissionais × 18 horários),
km evitados = 2 × distância até a unidade mais próxima sem o profissional, custo = km × R$ 1,20 (premissa editável). Código em <code>area2_dados/analitica.py</code>, com testes.</p>

<h2 class='quebra'>5. Área 3: MVP, IoT e experiência digital</h2>
<h3>5.1 Protótipo (8 telas funcionais)</h3>
<div class='par'><div>{img('tela1_entrada.png', 'Tela 1: entrada (login e cadastro com consentimento LGPD).')}</div><div>{img('tela2_triagem.png', 'Tela 2: triagem.')}</div></div>
<div class='par'><div>{img('tela3_resultado.png', 'Tela 3: resultado da triagem.')}</div><div>{img('tela9_emergencia.png', 'Tela 4: emergência.')}</div></div>
<div class='par'><div>{img('tela6_unidades.png', 'Tela 5: unidades, com filtros de serviço, especialidade e raio.')}</div><div>{img('tela5_painel.png', 'Tela 6: painel da unidade com os sensores simulados.')}</div></div>
<div class='par'><div>{img('tela7_agendar.png', 'Tela 7: agendar.')}</div><div>{img('tela8_agendamentos.png', 'Tela 8: meus agendamentos.')}</div></div>
<h3>5.2 IoT (3 sensores simulados, sem hardware)</h3>
<p>O simulador Python (<code>area3_mvp/iot_mock/simulador.py</code>) envia, por unidade, três tipos de evento para <code>POST /api/iot/eventos</code> com o cabeçalho <code>X-Device-Key</code>:
<b>fila</b> (<code>pessoas</code>), <b>presença</b> de profissional (<code>cbo</code>, <code>presente</code>) e <b>heartbeat</b> de conectividade (<code>online</code>, <code>latenciaMs</code>).
A chave é derivada por HMAC-SHA256 por unidade e tipo, e só o hash é guardado. Eventos são idempotentes por <code>eventId</code> (201 novo, 200 duplicado, 400, 401, 403).
Cinco cenários: <code>normal</code>, <code>ortopedista_ausente</code>, <code>fila_longa</code>, <code>posto_offline</code>, <code>pico_demanda</code>. Execução real: <code>ciclo 1/2: 272 eventos, novos 272, duplicados 0, rejeitados 0</code>.</p>
<h3>5.3 Fluxo funcional completo</h3>
<p>Cadastro e consentimento → login → "quebrei a perna jogando bola" → Ortopedia, muito urgente → aviso de que a unidade mais próxima não tem o profissional → unidade indicada com profissional presente →
painel da unidade (sensores) → agendamento (protocolo gerado) → "Meus agendamentos" → evento gravado no bronze → indicadores no dashboard. As telas acima mostram cada passo.</p>
<h3>5.4 Arquitetura</h3>
<p>Diagrama na seção 2.</p>

<h2 class='quebra'>6. Área 4: DevOps, segurança e qualidade</h2>
<h3>6.1 Organização e versionamento</h3>
<p>Módulos por área (<code>area1_ia</code>, <code>area2_dados</code>, <code>area3_mvp</code>, <code>area4_devops</code>), um README de execução e versionamento com Git (histórico de commits do repositório).</p>
<div class='preencher'><b>PREENCHER:</b> print do histórico do Git (<code>git log --oneline</code> ou a página de commits no GitHub).</div>
<h3>6.2 DevOps: Docker e CI/CD</h3>
<p>Dockerfile multi-stage da API, imagem Python única para simulador, gerador, ETL e dashboard, <code>docker-compose.yml</code> com perfis e workflow do GitHub Actions
(testes da API com cobertura, testes Python por área e build das imagens). Arquivos abaixo.</p>
<pre>{ci}</pre>
<pre>{dockerfile}</pre>
<div class='preencher'><b>PREENCHER (evidência de execução):</b> print da aba Actions do GitHub com o workflow <code>ci</code> verde, e/ou saída de <code>docker compose up --build</code>.
Os arquivos foram escritos e revisados, mas a construção das imagens e a execução do workflow ainda não foram feitas pelo grupo (sem Docker no ambiente de desenvolvimento).</div>
<details><summary>docker-compose.yml</summary><pre>{compose}</pre></details>
<h3>6.3 Segurança (mais de 2 práticas)</h3>
{seguranca}
<p>Sondagem ponta a ponta contra a API em execução (<code>area4_devops/sondagem_api.py</code>):</p>
<pre>{sondagem}</pre>
<h3>6.4 Plano de testes (18 casos) e evidências da execução</h3>
{plano}
<h3>Execução (<code>python area4_devops/verificar_tudo.py</code>)</h3>
{verificacao()}

<h2>7. Limitações declaradas</h2>
<ul><li>Fila, presença, conexão, demanda, catálogo de especialidades por unidade e impacto são simulados; unidades e coordenadas são reais (CNES).</li>
<li>Docker, GitHub Actions e chamada real a LLM ainda não foram executados com sucesso (ver caixas "PREENCHER").</li>
<li>As regras foram ajustadas no conjunto ouro; o conjunto de validação deixou de ser independente depois das correções.</li>
<li>A checagem de capacidade do agendamento não é atômica, o limite de requisições é em memória e o H2 perde dados ao reiniciar (há suporte a PostgreSQL).</li>
<li>A triagem não substitui avaliação médica, e o app diz isso na interface.</li></ul>

<h2>8. Fontes e créditos</h2>
<ul><li>Unidades de saúde: CNES/DATASUS (amostra de 51 unidades de São Paulo).</li>
<li>Classificação de risco: protocolo de Manchester. Códigos de ocupação: CBO.</li>
<li>Padrões reaproveitados de projetos anteriores do grupo: autenticação de dispositivo IoT por chave (PulseLink Beacon) e dashboard em Python (SmartAgro). Nenhum arquivo de credencial foi copiado.</li></ul>
""")
    return f"<!doctype html><html lang='pt-BR'><meta charset='utf-8'><title>PertoDeMim · Entregáveis</title><style>{CSS}</style><body>{''.join(p)}</body></html>"


def main() -> int:
    destino = RAIZ / "entrega" / "Entregaveis_PertoDeMim.pdf"
    with tempfile.TemporaryDirectory() as tmp:
        arq = Path(tmp) / "doc.html"
        arq.write_text(montar(), encoding="utf-8")
        destino.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([achar_navegador(), "--headless", "--disable-gpu", "--no-pdf-header-footer",
                        f"--print-to-pdf={destino.resolve()}", arq.as_uri()], check=True, capture_output=True, timeout=240)
    if not destino.exists() or destino.stat().st_size == 0:
        print("o navegador não gerou o PDF", file=sys.stderr)
        return 1
    print(f"{destino} ({destino.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
