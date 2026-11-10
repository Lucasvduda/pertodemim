"""Testes de estrutura do repositório: arquivos obrigatórios, segredos, Docker/compose/CI coerentes, documentação."""
import filecmp
import re
import sys
import zipfile
from pathlib import Path

import pytest
import yaml

RAIZ = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(RAIZ / "area4_devops"))
sys.path.insert(0, str(RAIZ / "entrega"))
import montar_entrega  # noqa: E402
import segredos  # noqa: E402

API = RAIZ / "area3_mvp" / "api"


# ---------------------------------------------------------------- arquivos obrigatórios

OBRIGATORIOS = [
    "README.md", ".gitignore", ".env.example", "docker-compose.yml", ".github/workflows/ci.yml",
    "area1_ia/frases_ouro.csv", "area1_ia/avaliar_triagem.py", "area1_ia/simulacao_impacto.py", "area1_ia/requirements.txt",
    "area1_ia/resultados/conjunto_ouro_regras_1a_execucao.txt", "area1_ia/resultados/simulacao_impacto.md",
    "area2_dados/etl.py", "area2_dados/analitica.py", "area2_dados/dashboard.py", "area2_dados/dados_dashboard.py",
    "area2_dados/gerar_dados_exemplo.py", "area2_dados/requirements.txt", "area2_dados/data/dim_unidades.csv",
    "area3_mvp/api/pom.xml", "area3_mvp/api/Dockerfile", "area3_mvp/api/mvnw", "area3_mvp/api/mvnw.cmd",
    "area3_mvp/api/.mvn/wrapper/maven-wrapper.properties", "area3_mvp/api/src/main/resources/application.yaml",
    "area3_mvp/api/src/main/resources/static/index.html", "area3_mvp/api/src/main/resources/static/app.js",
    "area3_mvp/api/src/main/resources/static/styles.css",
    "area3_mvp/iot_mock/simulador.py", "area3_mvp/iot_mock/requirements.txt",
    "area4_devops/Dockerfile.python", "area4_devops/PLANO_DE_TESTES.md", "area4_devops/SEGURANCA.md",
    "area4_devops/verificar_tudo.py", "area4_devops/requirements.txt",
    "docs/ARQUITETURA.md", "docs/ROTEIRO_PITCH.md", "entrega/MODELO_entrega.txt", "entrega/montar_entrega.py",
]


@pytest.mark.parametrize("caminho", OBRIGATORIOS)
def test_arquivo_obrigatorio_existe_e_nao_esta_vazio(caminho):
    p = RAIZ / caminho
    assert p.is_file(), f"falta {caminho}"
    assert p.stat().st_size > 0, f"{caminho} está vazio"


@pytest.mark.parametrize("cenario", ["normal", "ortopedista_ausente", "fila_longa", "posto_offline", "pico_demanda"])
def test_cenarios_do_simulador_existem(cenario):
    assert (RAIZ / "area3_mvp" / "iot_mock" / "cenarios" / f"{cenario}.json").is_file()


def test_todo_python_compila():
    for p in RAIZ.rglob("*.py"):
        if any(x in p.parts for x in ("__pycache__", ".venv", "target")):
            continue
        compile(p.read_text(encoding="utf-8"), str(p), "exec")  # só valida a sintaxe, sem gravar .pyc


def test_seed_da_api_e_dimensoes_do_area2_sao_identicos():
    seed = API / "src" / "main" / "resources" / "seed"
    for a, b in [("unidades.csv", "dim_unidades.csv"), ("especialidades.csv", "dim_especialidades.csv"), ("servicos.csv", "dim_servicos.csv")]:
        assert filecmp.cmp(seed / a, RAIZ / "area2_dados" / "data" / b, shallow=False), f"{a} diverge de {b}"


# ---------------------------------------------------------------- segredos

def test_nao_existe_arquivo_env_nem_credenciais_no_repositorio():
    proibidos = [p for p in RAIZ.rglob("*") if p.is_file() and (p.name == ".env" or p.name.startswith("credenciais") or p.suffix in {".pem", ".key"})
                 and not any(x in p.parts for x in (".git", "target"))]
    assert proibidos == []


def test_nenhum_segredo_no_codigo():
    assert segredos.varrer(RAIZ) == []


@pytest.mark.parametrize("amostra, esperado", [
    ("GROQ=" + "gsk_" + "a" * 40, "chave Groq"),
    ("k=AIza" + "B" * 35, "chave Google/Gemini"),
    ("OPENAI=sk-" + "c" * 40, "chave estilo OpenAI"),
    ("t=ghp_" + "d" * 36, "token GitHub"),
    ("aws=AKIA" + "E" * 16, "chave AWS"),
    ("-----BEGIN " + "RSA PRIVATE KEY-----", "chave privada"),  # montada em partes para o scanner não acusar este arquivo
    ('password = "' + "x9" * 12 + '"', "credencial literal"),
    ("api_key: '" + "Zz1" * 8 + "'", "credencial literal"),
])
def test_o_detector_de_segredos_realmente_detecta(amostra, esperado):
    assert esperado in {nome for _, nome in segredos.varrer_texto(amostra)}


@pytest.mark.parametrize("amostra", [
    "GROQ_API_KEY=", "api-key: ${GROQ_API_KEY:}", "senha = uuid.uuid4().hex", 'token = "curto"',
    "secret: ${JWT_SECRET:dev-only-secret-troque-em-producao-0123456789abcdef}",
])
def test_o_detector_nao_acusa_falso_positivo_comum(amostra):
    assert segredos.varrer_texto(amostra) == []


def test_env_example_nao_tem_valores_secretos():
    texto = (RAIZ / ".env.example").read_text(encoding="utf-8")
    for chave in ("GROQ_API_KEY", "GEMINI_API_KEY", "OPENAI_API_KEY", "JWT_SECRET", "IOT_KEY_SEED"):
        assert re.search(rf"^{chave}=$", texto, re.M), f"{chave} deve estar vazio no .env.example"
    assert "LLM_PROVIDER=rules" in texto


def test_gitignore_protege_segredos_e_dados_gerados():
    g = (RAIZ / ".gitignore").read_text(encoding="utf-8")
    for item in (".env", "target/", "area2_dados/bronze/", "area2_dados/silver/", "area2_dados/gold/", "entrega/*.zip"):
        assert item in g.splitlines(), f"{item} deveria estar no .gitignore"


def test_api_usa_regras_por_padrao_e_nao_tem_chave_no_yaml():
    y = yaml.safe_load((API / "src" / "main" / "resources" / "application.yaml").read_text(encoding="utf-8"))
    assert y["app"]["llm"]["provider"] == "${LLM_PROVIDER:rules}"
    assert y["app"]["llm"]["groq"]["api-key"] == "${GROQ_API_KEY:}"
    assert y["management"]["endpoints"]["web"]["exposure"]["include"] == "health,info"
    assert y["server"]["error"]["include-stacktrace"] == "never"


# ---------------------------------------------------------------- Docker, compose, CI

def test_dockerfile_da_api_e_multistage_sem_root_e_com_healthcheck():
    d = (API / "Dockerfile").read_text(encoding="utf-8")
    assert len(re.findall(r"^FROM ", d, re.M)) == 2
    assert re.search(r"^USER \S+", d, re.M) and "USER root" not in d
    assert "HEALTHCHECK" in d and "/actuator/health" in d
    assert ":latest" not in d
    assert "pertodemim-api-1.0.0.jar" in d


def test_jar_do_dockerfile_bate_com_o_pom():
    pom = (API / "pom.xml").read_text(encoding="utf-8")
    art = re.search(r"<artifactId>(pertodemim-api)</artifactId>\s*<version>([^<]+)</version>", pom)
    assert art and f"{art.group(1)}-{art.group(2)}.jar" in (API / "Dockerfile").read_text(encoding="utf-8")


def test_dockerfile_python_sem_root_e_copia_o_que_precisa():
    d = (RAIZ / "area4_devops" / "Dockerfile.python").read_text(encoding="utf-8")
    assert re.search(r"^USER \S+", d, re.M) and ":latest" not in d
    for trecho in ("area2_dados", "area3_mvp/iot_mock", "area1_ia"):
        assert trecho in d


def test_compose_tem_os_servicos_e_nenhum_segredo_literal():
    texto = (RAIZ / "docker-compose.yml").read_text(encoding="utf-8")
    c = yaml.safe_load(texto)
    assert set(c["services"]) == {"api", "simulador", "gerar-exemplo", "etl", "dashboard"}
    assert c["services"]["api"]["ports"] == ["8080:8080"] and c["services"]["dashboard"]["ports"] == ["8501:8501"]
    assert set(c["volumes"]) == {"bronze", "silver", "gold"}
    for nome, s in c["services"].items():
        for k, v in (s.get("environment") or {}).items():
            if any(x in k for x in ("KEY", "SECRET", "SEED")):
                assert str(v).startswith("${"), f"{nome}.{k} deve vir do ambiente, não literal"
    assert c["services"]["simulador"]["depends_on"]["api"]["condition"] == "service_healthy"
    assert "${LLM_PROVIDER:-rules}" in texto


def test_compose_dashboard_le_gold_somente_leitura():
    c = yaml.safe_load((RAIZ / "docker-compose.yml").read_text(encoding="utf-8"))
    assert all(v.endswith(":ro") for v in c["services"]["dashboard"]["volumes"])


def test_compose_api_e_etl_compartilham_o_mesmo_volume_do_bronze():
    c = yaml.safe_load((RAIZ / "docker-compose.yml").read_text(encoding="utf-8"))["services"]
    assert any(v.startswith("bronze:") for v in c["api"]["volumes"]) and any(v.startswith("bronze:") for v in c["etl"]["volumes"])
    assert c["api"]["environment"]["BRONZE_DIR"] == "/data/bronze"


def test_ci_roda_maven_pytest_nas_4_pastas_e_docker_sem_permissoes_amplas():
    ci = yaml.safe_load((RAIZ / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8"))
    texto = (RAIZ / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
    assert ci["permissions"] == {"contents": "read"}
    assert "pull_request_target" not in texto
    assert "mvn -B verify" in texto
    assert ci["jobs"]["python"]["strategy"]["matrix"]["area"] == ["area1_ia", "area2_dados", "area3_mvp/iot_mock", "area4_devops"]
    assert "docker compose config -q" in texto
    for pasta in ci["jobs"]["python"]["strategy"]["matrix"]["area"]:
        assert (RAIZ / pasta / "tests").is_dir(), f"{pasta} não tem tests/"


def test_wrapper_do_maven_aponta_para_uma_versao_valida():
    props = (API / ".mvn" / "wrapper" / "maven-wrapper.properties").read_text(encoding="utf-8")
    assert re.search(r"apache-maven/3\.\d+\.\d+/apache-maven-3\.\d+\.\d+-bin\.zip", props)


# ---------------------------------------------------------------- documentação

def test_readme_tem_as_secoes_esperadas_e_a_secao_de_ia_existe():
    r = (RAIZ / "README.md").read_text(encoding="utf-8")
    for sec in ("## As quatro áreas", "## Rodar a API e o simulador", "## Triagem e IA", "## Dados e dashboard", "## Contrato IoT",
                "## Docker", "## Testes", "## Limitações conhecidas", "## Uso de IA generativa", "## Integrantes"):
        assert sec in r, f"README sem a seção {sec}"
    assert "simulad" in r.lower() and "não foram executados" in r


def test_comandos_do_readme_apontam_para_arquivos_que_existem():
    r = (RAIZ / "README.md").read_text(encoding="utf-8")
    for arq in re.findall(r"python (?:area4_devops/)?([\w/]+\.py)", r):
        achados = list(RAIZ.rglob(Path(arq).name))
        assert achados, f"README cita {arq}, que não existe"


def test_plano_de_testes_cita_apenas_arquivos_que_existem():
    plano = (RAIZ / "area4_devops" / "PLANO_DE_TESTES.md").read_text(encoding="utf-8")
    base = "area3_mvp/api/src/test/java/br/com/pertodemim"
    citados = set(re.findall(r"`((?:J|area\d_\w+)/[\w/\.]+\.(?:java|py))`", plano))
    assert len(citados) >= 25
    for c in citados:
        caminho = c.replace("J/", base + "/", 1) if c.startswith("J/") else c
        assert (RAIZ / caminho).is_file(), f"plano cita {c}, que não existe"


def test_todo_teste_java_esta_no_plano():
    plano = (RAIZ / "area4_devops" / "PLANO_DE_TESTES.md").read_text(encoding="utf-8")
    for p in (API / "src" / "test" / "java").rglob("*Test.java"):
        assert p.name in plano, f"{p.name} não está no plano de testes"


def test_todo_arquivo_de_teste_python_esta_no_plano():
    plano = (RAIZ / "area4_devops" / "PLANO_DE_TESTES.md").read_text(encoding="utf-8")
    for p in RAIZ.rglob("test_*.py"):
        if "__pycache__" not in p.parts:
            assert p.name in plano, f"{p.name} não está no plano de testes"


def test_roteiro_do_pitch_nao_descreve_uso_de_ia_pelo_assistente():
    r = (RAIZ / "docs" / "ROTEIRO_PITCH.md").read_text(encoding="utf-8")
    assert "PREENCHER PELO GRUPO" in r


def test_modelo_de_entrega_deixa_campos_em_branco_para_o_grupo():
    t = (RAIZ / "entrega" / "MODELO_entrega.txt").read_text(encoding="utf-8")
    assert t.count("<PREENCHER") >= 4


# ---------------------------------------------------------------- montagem do zip

def test_zip_da_entrega_nao_leva_lixo_nem_segredos(tmp_path):
    zip_path = tmp_path / "e.zip"
    montar_entrega.montar(zip_path)
    with zipfile.ZipFile(zip_path) as z:
        nomes = z.namelist()
    assert "pertodemim/README.md" in nomes and "pertodemim/docker-compose.yml" in nomes
    for n in nomes:
        partes = set(Path(n).parts)
        assert not partes & {"target", "__pycache__", ".git", ".pytest_cache", "bronze", "silver", "gold", "buffer"}, n
        assert not n.endswith((".pyc", ".log", ".zip", ".env")), n


def test_montagem_do_zip_recusa_projeto_com_segredo(tmp_path):
    (tmp_path / "area4_devops").mkdir()
    (tmp_path / "codigo.py").write_text('GROQ = "' + "gsk_" + "z" * 40 + '"\n', encoding="utf-8")
    with pytest.raises(RuntimeError, match="possível segredo"):
        montar_entrega.montar(tmp_path / "out.zip", raiz=tmp_path)
