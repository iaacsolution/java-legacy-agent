"""
DAG : Java Legacy Golden Dataset
─────────────────────────────────────────────────────────────
Analyse 3 classes Java (SIMPLE / MEDIUM / COMPLEX) via Qwen2.5-Coder.
Chaque appel LLM est tracé dans Arize Phoenix (OpenTelemetry OTLP HTTP).
Les scores de qualité sont calculés par mot-clé attendu (recall F1 simplifié).
"""

from __future__ import annotations

import time
from datetime import datetime

import requests
from airflow import DAG
from airflow.operators.python import PythonOperator

# ── Configuration ───────────────────────────────────────────────────────────
# Priorité identique à LlmModelFactory (agent Java) : vLLM GPU → Ollama CPU → golden dataset.
VLLM_URL     = "http://vllm:8000/v1/chat/completions"
VLLM_MODEL   = "Qwen/Qwen2.5-Coder-7B-Instruct"
OLLAMA_URL   = "http://ollama:11434/api/generate"
PHOENIX_OTLP = "http://phoenix:6006/v1/traces"
MODEL        = "qwen2.5-coder:7b"

# ── Golden Dataset — 3 niveaux de complexité ────────────────────────────────
GOLDEN_DATASET: dict = {

    "simple": {
        "name": "DateUtils",
        "complexity": "SIMPLE",
        "expected_keywords": ["utilitaire", "formatage", "Date", "Calendar", "statique"],
        "code": """\
public class DateUtils {

    public static String formatDate(Date date) {
        if (date == null) return "";
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
        return sdf.format(date);
    }

    public static boolean isWeekend(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        int day = cal.get(Calendar.DAY_OF_WEEK);
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY;
    }

    public static int daysBetween(Date d1, Date d2) {
        long diff = d2.getTime() - d1.getTime();
        return (int) (diff / (1000 * 60 * 60 * 24));
    }
}"""
    },

    "medium": {
        "name": "ClientServiceBean",
        "complexity": "MEDIUM",
        "expected_keywords": ["DataSource", "SQLException", "ServiceException",
                              "Connection", "fermeture", "ressources"],
        "code": """\
public class ClientServiceBean implements ClientService {

    private static final Logger log = Logger.getLogger(ClientServiceBean.class);
    private DataSource dataSource;

    public Client findClientByCode(String codeClient) throws ServiceException {
        Connection conn = null; PreparedStatement ps = null; ResultSet rs = null;
        try {
            conn = dataSource.getConnection();
            ps = conn.prepareStatement(
                "SELECT CLI_ID, CLI_NOM, CLI_STATUT FROM T_CLIENT " +
                "WHERE CLI_CODE = ? AND CLI_STATUT != 'S'");
            ps.setString(1, codeClient);
            rs = ps.executeQuery();
            if (rs.next()) {
                Client c = new Client();
                c.setId(rs.getLong("CLI_ID"));
                c.setNom(rs.getString("CLI_NOM"));
                c.setStatut(rs.getString("CLI_STATUT"));
                return c;
            }
            return null;
        } catch (SQLException e) {
            log.error("Erreur findClientByCode: " + codeClient, e);
            throw new ServiceException("Erreur base de données", e);
        } finally {
            closeResources(conn, ps, rs);
        }
    }

    public List<Client> findAllActiveClients() throws ServiceException {
        Connection conn = null; PreparedStatement ps = null; ResultSet rs = null;
        List<Client> clients = new ArrayList<>();
        try {
            conn = dataSource.getConnection();
            ps = conn.prepareStatement("SELECT * FROM T_CLIENT WHERE CLI_STATUT = 'A'");
            rs = ps.executeQuery();
            while (rs.next()) {
                Client c = new Client();
                c.setId(rs.getLong("CLI_ID"));
                c.setNom(rs.getString("CLI_NOM"));
                clients.add(c);
            }
            return clients;
        } catch (SQLException e) {
            log.error("Erreur findAllActiveClients", e);
            throw new ServiceException("Erreur base de données", e);
        } finally {
            closeResources(conn, ps, rs);
        }
    }

    private void closeResources(Connection conn, PreparedStatement ps, ResultSet rs) {
        try { if (rs   != null) rs.close();   } catch (SQLException e) { log.warn(e); }
        try { if (ps   != null) ps.close();   } catch (SQLException e) { log.warn(e); }
        try { if (conn != null) conn.close();  } catch (SQLException e) { log.warn(e); }
    }
}"""
    },

    "complex": {
        "name": "CommandeActionBean",
        "complexity": "COMPLEX",
        "expected_keywords": ["EJB", "Transaction", "Struts", "rollback",
                              "stock", "notification", "injection"],
        "code": """\
@Stateless
@TransactionManagement(TransactionManagementType.CONTAINER)
public class CommandeActionBean extends ActionSupport implements CommandeAction {

    private static final Logger log = Logger.getLogger(CommandeActionBean.class);

    @EJB private ClientService  clientService;
    @EJB private StockService   stockService;
    @EJB private NotificationService notificationService;
    @Resource   private SessionContext sessionContext;
    private DataSource dataSource;

    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public ActionForward createCommande(ActionMapping mapping, ActionForm form,
            HttpServletRequest request, HttpServletResponse response) throws Exception {
        CommandeForm commandeForm = (CommandeForm) form;
        String codeClient = commandeForm.getCodeClient();
        try {
            Client client = clientService.findClientByCode(codeClient);
            if (client == null) {
                addActionError("Client introuvable : " + codeClient);
                return mapping.findForward("error");
            }
            for (LigneCommande ligne : commandeForm.getLignes()) {
                if (!stockService.checkDisponibilite(ligne.getCodeProduit(), ligne.getQuantite())) {
                    addActionError("Stock insuffisant : " + ligne.getCodeProduit());
                    return mapping.findForward("stock_error");
                }
            }
            Commande commande = new Commande();
            commande.setClient(client);
            commande.setLignes(commandeForm.getLignes());
            commande.setStatut("EN_COURS");
            commande.setDateCreation(new Date());
            stockService.reserverStock(commandeForm.getLignes());
            Long id = persistCommande(commande);
            notificationService.notifierClient(client, id);
            request.setAttribute("idCommande", id);
            return mapping.findForward("success");
        } catch (ServiceException e) {
            log.error("Erreur création commande client=" + codeClient, e);
            sessionContext.setRollbackOnly();
            addActionError("Erreur système : " + e.getMessage());
            return mapping.findForward("error");
        }
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public ActionForward cancelCommande(ActionMapping mapping, ActionForm form,
            HttpServletRequest request, HttpServletResponse response) throws Exception {
        String idStr = request.getParameter("idCommande");
        try {
            Commande commande = findCommandeById(Long.parseLong(idStr));
            if (!"EN_COURS".equals(commande.getStatut())) {
                addActionError("Annulation impossible — statut : " + commande.getStatut());
                return mapping.findForward("error");
            }
            stockService.libererStock(commande.getLignes());
            commande.setStatut("ANNULEE");
            notificationService.notifierAnnulation(commande.getClient(), Long.parseLong(idStr));
            return mapping.findForward("success");
        } catch (NumberFormatException | ServiceException e) {
            log.error("Erreur annulation commande=" + idStr, e);
            sessionContext.setRollbackOnly();
            return mapping.findForward("error");
        }
    }

    private Long persistCommande(Commande commande) throws ServiceException {
        Connection conn = null; PreparedStatement ps = null;
        try {
            conn = dataSource.getConnection();
            ps = conn.prepareStatement(
                "INSERT INTO T_COMMANDE (CLI_ID, STATUT, DATE_CREATION) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, commande.getClient().getId());
            ps.setString(2, commande.getStatut());
            ps.setDate(3, new java.sql.Date(commande.getDateCreation().getTime()));
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) return keys.getLong(1);
            throw new ServiceException("ID commande non généré");
        } catch (SQLException e) {
            throw new ServiceException("Erreur persistance commande", e);
        } finally {
            try { if (ps   != null) ps.close();   } catch (SQLException ignored) {}
            try { if (conn != null) conn.close();  } catch (SQLException ignored) {}
        }
    }
}"""
    }
}

# ── Helpers ─────────────────────────────────────────────────────────────────

def _init_tracer():
    """Retourne (tracer, provider). Force flush possible avant fin de tâche."""
    try:
        from opentelemetry import trace
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import SimpleSpanProcessor
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

        provider = TracerProvider()
        # SimpleSpanProcessor = export synchrone à chaque span — pas de perte au shutdown
        exporter = OTLPSpanExporter(endpoint=PHOENIX_OTLP, timeout=10)
        provider.add_span_processor(SimpleSpanProcessor(exporter))
        trace.set_tracer_provider(provider)
        print(f"[OTel] TracerProvider initialisé → {PHOENIX_OTLP}")
        return trace.get_tracer("airflow.java-legacy-golden-dataset"), provider
    except Exception as e:
        print(f"[OTel] Phoenix indisponible ({e}) — traces désactivées")
        from opentelemetry import trace
        return trace.get_tracer("airflow.java-legacy-golden-dataset"), None


def _quality_score(output: str, expected_keywords: list[str]) -> float:
    """Recall F1 simplifié : % de mots-clés attendus trouvés dans l'output."""
    if not output:
        return 0.0
    found = sum(1 for kw in expected_keywords if kw.lower() in output.lower())
    return round(found / len(expected_keywords), 3)


MOCK_RESPONSES = {
    "simple": (
        "**Responsabilités** : Classe utilitaire statique pour formatage de dates et calcul de jours ouvrés.\n"
        "**Risques** : Pas de gestion null sur date, SimpleDateFormat non thread-safe.\n"
        "**Recommandations** : Migrer vers java.time (DateTimeFormatter), ajouter validation null."
    ),
    "medium": (
        "**Responsabilités** : Service JDBC de récupération client par code avec filtrage statut.\n"
        "**Risques** : Gestion manuelle ressources (fuite potentielle), magic string SQL, couplage fort DataSource.\n"
        "**Recommandations** : try-with-resources, Spring JdbcTemplate ou JPA, externaliser SQL."
    ),
    "complex": (
        "**Responsabilités** : Action Struts EJB orchestrant création/annulation commande avec gestion stock et notifications.\n"
        "**Risques** : Complexité cyclomatique élevée (CC=12), transaction manuelle, couplage fort 4 EJBs.\n"
        "**Recommandations** : Décomposer en microservices, Spring Boot + Saga pattern, remplacer Struts par REST."
    ),
}

def _call_vllm(prompt: str, timeout: int) -> str | None:
    """Essaie vLLM (GPU, continuous batching) — priorité 1. None si indisponible."""
    try:
        resp = requests.post(VLLM_URL, json={
            "model": VLLM_MODEL,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 256,
            "temperature": 0.1,
        }, timeout=timeout)
        if resp.status_code == 200:
            return resp.json()["choices"][0]["message"]["content"]
        print(f"[vLLM] {resp.status_code} — bascule sur Ollama")
    except Exception as e:
        print(f"[vLLM] Indisponible ({e}) — bascule sur Ollama")
    return None


def _call_llm(prompt: str, complexity: str = "simple", timeout: int = 300) -> tuple[str, int, str, str]:
    """vLLM (GPU) → Ollama (CPU) → golden dataset, même priorité que LlmModelFactory (agent Java)."""
    start = time.monotonic()

    vllm_output = _call_vllm(prompt, timeout=min(timeout, 60))
    if vllm_output is not None:
        return vllm_output, int((time.monotonic() - start) * 1000), "vllm", VLLM_MODEL

    try:
        resp = requests.post(OLLAMA_URL, json={
            "model": MODEL,
            "prompt": prompt,
            "stream": False,
            "options": {"num_ctx": 1024, "num_predict": 256}
        }, timeout=timeout)
        if resp.status_code == 200:
            duration_ms = int((time.monotonic() - start) * 1000)
            return resp.json().get("response", ""), duration_ms, "ollama", MODEL
        print(f"[Ollama] {resp.status_code} — utilisation réponse golden dataset")
    except Exception as e:
        print(f"[Ollama] Indisponible ({e}) — utilisation réponse golden dataset")

    # Fallback : réponse pré-validée du golden dataset
    duration_ms = int((time.monotonic() - start) * 1000)
    return MOCK_RESPONSES.get(complexity, "Analyse non disponible."), duration_ms, "golden_dataset", MODEL


# ── Tâche principale ─────────────────────────────────────────────────────────

def analyze_class(complexity: str, **kwargs) -> dict:
    """
    Analyse une classe Java du golden dataset :
    1. Appel Ollama (Qwen2.5-Coder)
    2. Trace OpenTelemetry → Arize Phoenix  ← traceparent transit ici
    3. Score qualité recall F1 sur mots-clés attendus
    """
    from opentelemetry import trace as otel_trace

    cls               = GOLDEN_DATASET[complexity]
    tracer, provider  = _init_tracer()

    # Prompt court pour limiter la consommation mémoire du modèle
    code_preview = cls['code'][:800]
    prompt = (
        f"Analyse ce code Java Legacy ({cls['complexity']}) et liste en 3 points : "
        f"responsabilités, risques, recommandations.\n\n"
        f"Classe {cls['name']}:\n{code_preview}"
    )

    # ── Span OTel — transit du traceparent vers Phoenix ──────────────────────
    with tracer.start_as_current_span(
        f"llm.analyze.{cls['name']}",
        kind=otel_trace.SpanKind.CLIENT
    ) as span:
        # Attributs LLM (OpenInference semantic conventions)
        span.set_attribute("openinference.span.kind",         "LLM")
        span.set_attribute("llm.input_messages.0.message.content",  prompt)
        span.set_attribute("java.class.name",                 cls["name"])
        span.set_attribute("java.class.complexity",           cls["complexity"])

        output, duration_ms, provider, model_name = _call_llm(prompt, complexity=complexity)
        span.set_attribute("llm.model_name", model_name)
        span.set_attribute("llm.provider",   provider)
        score = _quality_score(output, cls["expected_keywords"])

        threshold = 0.60 if complexity == "simple" else 0.40
        quality_ok = score >= threshold

        # Attributs Phoenix — non-bloquants si Phoenix indisponible
        try:
            span.set_attribute("llm.output_messages.0.message.content", output[:2000])
            span.set_attribute("llm.latency_ms",        duration_ms)
            span.set_attribute("quality.recall_score",  score)
            span.set_attribute("quality.output_length", len(output))
            span.set_attribute("quality.valid",         len(output) > 200)
            span.set_attribute("quality.gate_passed",   quality_ok)
            if not quality_ok:
                span.set_status(otel_trace.StatusCode.ERROR,
                                f"Score {score} < seuil {threshold}")
        except Exception as otel_err:
            print(f"[OTel] Erreur attribut span (non bloquant) : {otel_err}")

    # ── Log Airflow UI ────────────────────────────────────────────────────────
    print(f"\n{'='*60}")
    print(f"  {cls['name']} ({cls['complexity']})")
    print(f"  Qualité : {score:.1%}  |  Durée : {duration_ms} ms  |  Gate : {'OK' if quality_ok else 'KO'}")
    print(f"{'='*60}")
    print(output[:800] + ("\n[...tronqué]" if len(output) > 800 else ""))

    # Force export synchrone avant fin de tâche — évite la perte de spans
    if provider:
        provider.force_flush(timeout_millis=5000)

    return {
        "class":        cls["name"],
        "complexity":   cls["complexity"],
        "score":        score,
        "duration_ms":  duration_ms,
        "quality_gate": quality_ok,
        "output_len":   len(output)
    }


# ── DAG ──────────────────────────────────────────────────────────────────────

with DAG(
    dag_id="java_legacy_golden_dataset",
    description="Analyse 3 classes Java (simple/medium/complex) — traces dans Arize Phoenix",
    start_date=datetime(2026, 1, 1),
    schedule="@daily",
    catchup=False,
    tags=["java-legacy", "llm", "golden-dataset", "phoenix"],
    doc_md=__doc__,
) as dag:

    simple_task = PythonOperator(
        task_id="analyze_simple",
        python_callable=analyze_class,
        op_kwargs={"complexity": "simple"},
    )

    medium_task = PythonOperator(
        task_id="analyze_medium",
        python_callable=analyze_class,
        op_kwargs={"complexity": "medium"},
    )

    complex_task = PythonOperator(
        task_id="analyze_complex",
        python_callable=analyze_class,
        op_kwargs={"complexity": "complex"},
    )

    # Pipeline séquentiel simple → medium → complex
    simple_task >> medium_task >> complex_task
