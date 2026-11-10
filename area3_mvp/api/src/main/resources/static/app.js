/* PertoDeMim: front-end sem framework. Todo texto vindo da API entra com textContent (nunca como HTML). */
(function () {
  'use strict';

  var TELAS = ['entrada', 'triagem', 'emergencia', 'resultado', 'unidades', 'agendar', 'agendamentos', 'painel'];
  var estado = {
    token: sessionStorage.getItem('pdm_token'),
    nome: sessionStorage.getItem('pdm_nome'),
    lat: null, lon: null,
    ultimaTriagem: null,
    agendar: null,        // {unidadeId, unidadeNome, servico, cbo, especialidade}
    horarioEscolhido: null,
    painelTimer: null,
    especialidades: []
  };

  function $(id) { return document.getElementById(id); }

  /** Cria elemento com texto seguro. */
  function el(tag, props, filhos) {
    var e = document.createElement(tag);
    if (props) {
      Object.keys(props).forEach(function (k) {
        if (k === 'class') e.className = props[k];
        else if (k === 'text') e.textContent = props[k];
        else if (k.indexOf('on') === 0) e.addEventListener(k.slice(2), props[k]);
        else e.setAttribute(k, props[k]);
      });
    }
    (filhos || []).forEach(function (f) { if (f) e.appendChild(typeof f === 'string' ? document.createTextNode(f) : f); });
    return e;
  }

  function limpar(no) { while (no.firstChild) no.removeChild(no.firstChild); }

  function aviso(msg, ok) {
    var a = $('aviso');
    if (!msg) { a.hidden = true; a.textContent = ''; return; }
    a.textContent = msg;
    a.className = 'aviso' + (ok ? ' ok' : '');
    a.hidden = false;
    a.scrollIntoView({ block: 'nearest' });
  }

  function mostrar(nome) {
    pararPainel();
    TELAS.forEach(function (t) { $('tela-' + t).hidden = (t !== nome); });
    $('menu').hidden = !estado.token;
    aviso(null);
    var main = $('principal');
    main.focus({ preventScroll: true });
    window.scrollTo(0, 0);
  }

  function sair() {
    estado.token = null; estado.nome = null;
    sessionStorage.removeItem('pdm_token'); sessionStorage.removeItem('pdm_nome');
    mostrar('entrada');
  }

  function api(metodo, url, corpo) {
    var opc = { method: metodo, headers: { 'Accept': 'application/json' } };
    if (corpo !== undefined) { opc.headers['Content-Type'] = 'application/json'; opc.body = JSON.stringify(corpo); }
    if (estado.token) opc.headers['Authorization'] = 'Bearer ' + estado.token;
    return fetch(url, opc).then(function (r) {
      return r.text().then(function (t) {
        var dados = null;
        try { dados = t ? JSON.parse(t) : null; } catch (e) { dados = null; }
        if (r.ok) return dados;
        if (r.status === 401 && estado.token) { sair(); throw new Error('Sua sessão expirou. Entre novamente.'); }
        if (r.status === 429) throw new Error('Muitas tentativas. Aguarde um minuto e tente de novo.');
        var msg = (dados && dados.erro) || 'Não foi possível concluir. Tente novamente.';
        if (dados && dados.campos) msg += ': ' + Object.keys(dados.campos).map(function (k) { return dados.campos[k]; }).join('; ');
        var erro = new Error(msg); erro.status = r.status; throw erro;
      });
    });
  }

  function erro(e) { aviso(e && e.message ? e.message : 'Erro inesperado. Tente novamente.'); }

  /* ---------- entrada ---------- */
  function iniciarEntrada() {
    $('form-login').addEventListener('submit', function (ev) {
      ev.preventDefault();
      var f = ev.target;
      if (!f.email.value || !f.senha.value) return aviso('Informe e-mail e senha.');
      api('POST', '/api/auth/login', { email: f.email.value.trim(), senha: f.senha.value }).then(function (r) {
        estado.token = r.token; estado.nome = r.nome;
        sessionStorage.setItem('pdm_token', r.token); sessionStorage.setItem('pdm_nome', r.nome || '');
        f.senha.value = '';
        carregarEspecialidades().then(function () { mostrar('triagem'); });
      }).catch(erro);
    });
    $('form-cadastro').addEventListener('submit', function (ev) {
      ev.preventDefault();
      var f = ev.target;
      if (!f.consentimento.checked) return aviso('Para criar a conta, aceite o termo de consentimento.');
      api('POST', '/api/auth/register', {
        nome: f.nome.value.trim(), email: f.email.value.trim(), senha: f.senha.value, consentimento: true
      }).then(function () {
        aviso('Conta criada. Agora entre com o seu e-mail e senha.', true);
        f.reset();
      }).catch(erro);
    });
    $('abrir-termo').addEventListener('click', function () { $('termo').showModal(); });
  }

  /* ---------- triagem ---------- */
  function iniciarTriagem() {
    var txt = $('triagem-texto');
    txt.addEventListener('input', function () { $('contador').textContent = txt.value.length + '/500'; });
    $('btn-local').addEventListener('click', function () {
      if (!navigator.geolocation) return aviso('Seu navegador não permite localização.');
      navigator.geolocation.getCurrentPosition(function (p) {
        estado.lat = p.coords.latitude; estado.lon = p.coords.longitude;
        $('local-info').textContent = 'Localização usada só nesta busca.';
      }, function () { aviso('Não foi possível obter a localização. Vamos usar o centro de São Paulo.'); },
      { timeout: 8000, maximumAge: 60000 });
    });
    $('form-triagem').addEventListener('submit', function (ev) {
      ev.preventDefault();
      var texto = txt.value.trim();
      if (!texto) return aviso('Descreva o que você precisa.');
      if (!$('triagem-consent').checked) return aviso('Marque a concordância para enviar o texto.');
      var botao = $('btn-triar'); botao.disabled = true; botao.textContent = 'Analisando...';
      var corpo = { texto: texto, consentimento: true };
      if (estado.lat !== null) { corpo.lat = estado.lat; corpo.lon = estado.lon; }
      api('POST', '/api/triagem', corpo).then(function (r) {
        estado.ultimaTriagem = r;
        if (r.emergencia) mostrarEmergencia(r); else mostrarResultado(r);
      }).catch(erro).then(function () { botao.disabled = false; botao.textContent = 'Ver o que fazer'; });
    });
  }

  function mostrarEmergencia(r) {
    $('emerg-orientacao').textContent = r.orientacao;
    var tel = r.telefoneEmergencia || '192';
    var a = $('emerg-ligar');
    a.textContent = 'Ligar ' + tel;
    a.setAttribute('href', 'tel:' + tel.replace(/[^0-9]/g, ''));
    $('emerg-aviso').textContent = r.aviso;
    mostrar('emergencia');
  }

  function textoUrgencia(u) {
    return { vermelho: 'Emergência', laranja: 'Muito urgente', amarelo: 'Urgente', verde: 'Pouco urgente', azul: 'Não urgente' }[u] || u;
  }

  function km(v) { return Number(v).toFixed(1).replace('.', ',') + ' km'; }

  /** Mostra o ganho: a unidade mais próxima não tinha o profissional agora, então o app indicou outra. */
  function textoComparacao(r) {
    if (r.unidadeIndicadaId == null || r.maisProximaTinha !== false || r.kmMaisProxima == null || r.kmIndicada == null) return '';
    return 'A unidade mais próxima (' + km(r.kmMaisProxima) + ') não tem este atendimento agora. ' +
      'Indicamos uma a ' + km(r.kmIndicada) + ' para você não perder a viagem.';
  }

  function mostrarResultado(r) {
    var selo = $('res-urgencia');
    selo.className = 'selo ' + r.urgencia;
    selo.textContent = textoUrgencia(r.urgencia);
    $('res-especialidade').textContent = r.especialidade ||
      (r.servico === 'documento' ? 'Documentos' : r.servico === 'assistencia_social' ? 'Assistência social' : 'Saúde');
    $('res-orientacao').textContent = r.orientacao;
    var comp = $('res-comparacao'), textoComp = textoComparacao(r);
    if (textoComp) { comp.textContent = textoComp; comp.hidden = false; } else { comp.hidden = true; }
    var p = $('res-pergunta');
    if (r.perguntaAdicional) { p.textContent = r.perguntaAdicional; p.hidden = false; } else { p.hidden = true; }
    $('res-aviso').textContent = r.aviso;
    $('copiado').textContent = '';
    desenharUnidades($('lista-unidades'), $('sem-unidades'), r.unidades, r.unidadeIndicadaId,
      { servico: r.servico, cbo: r.cbo, especialidade: r.especialidade });
    mostrar('resultado');
  }

  function rotuloCategoria(u) {
    if (u.categoria === 'AGORA') return el('span', { class: 'etiqueta', text: 'Profissional presente agora' });
    if (u.categoria === 'AGENDA') return el('span', { class: 'etiqueta agenda', text: 'Agenda aberta' });
    return el('span', { class: 'etiqueta sem', text: 'Sem confirmação' });
  }

  function desenharUnidades(lista, vazio, unidades, indicadaId, ctx) {
    limpar(lista);
    vazio.hidden = unidades && unidades.length > 0;
    (unidades || []).forEach(function (u) {
      var classe = 'unidade' + (u.categoria === 'AGENDA' ? ' agenda' : u.categoria === 'SEM_CONFIRMACAO' ? ' sem' : '');
      var meta = u.distanciaKm + ' km · ' + u.tipo + (u.bairro ? ' · ' + u.bairro : '');
      if (u.esperaMin !== null && u.esperaMin !== undefined) meta += ' · espera estimada ' + u.esperaMin + ' min';
      var titulo = el('h3', { text: u.nome });
      var cabeca = [rotuloCategoria(u)];
      if (indicadaId && u.id === indicadaId) cabeca.unshift(el('span', { class: 'etiqueta indicada', text: 'Indicada' }));
      var acoes = el('div', { class: 'acoes' });
      if (u.categoria !== 'SEM_CONFIRMACAO') {
        acoes.appendChild(el('button', {
          type: 'button', class: 'primario', text: 'Agendar',
          onclick: function () { abrirAgendar(u, ctx); }
        }));
      }
      acoes.appendChild(el('button', {
        type: 'button', class: 'secundario', text: 'Ver painel da unidade',
        onclick: function () { abrirPainel(u.id, u.nome); }
      }));
      var li = el('li', { class: classe }, [
        el('div', null, cabeca), titulo, el('div', { class: 'meta', text: meta }),
        u.aviso ? el('div', { class: 'alerta', text: u.aviso }) : null, acoes
      ]);
      lista.appendChild(li);
    });
  }

  function copiarResumo() {
    var r = estado.ultimaTriagem;
    if (!r) return;
    var texto = r.resumo + ' ' + r.aviso;
    var fim = function () { $('copiado').textContent = 'Copiado.'; };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(texto).then(fim, function () { $('copiado').textContent = texto; });
    } else { $('copiado').textContent = texto; }
  }

  /* ---------- unidades (busca sem triagem) ---------- */
  function carregarEspecialidades() {
    if (estado.especialidades.length) return Promise.resolve();
    return api('GET', '/api/especialidades').then(function (lista) {
      estado.especialidades = lista;
      var sel = $('un-esp'); limpar(sel);
      lista.forEach(function (e) { sel.appendChild(el('option', { value: e.cbo, text: e.nome })); });
      sel.value = '225270';
    }).catch(function () { /* o próprio menu mostra o erro ao usar */ });
  }

  function iniciarUnidades() {
    $('un-servico').addEventListener('change', function () {
      var saude = $('un-servico').value === 'saude';
      $('un-esp').hidden = !saude; $('lbl-un-esp').hidden = !saude;
    });
    $('form-unidades').addEventListener('submit', function (ev) {
      ev.preventDefault();
      var servico = $('un-servico').value;
      var raio = parseFloat($('un-raio').value);
      if (!(raio >= 1 && raio <= 200)) return aviso('O raio deve ficar entre 1 e 200 km.');
      var q = '?servico=' + encodeURIComponent(servico) + '&raioKm=' + raio;
      if (servico === 'saude') q += '&especialidade=' + encodeURIComponent($('un-esp').value);
      if (estado.lat !== null) q += '&lat=' + estado.lat + '&lon=' + estado.lon;
      var cbo = servico === 'saude' ? $('un-esp').value : null;
      var nomeEsp = servico === 'saude' ? $('un-esp').selectedOptions[0].textContent : null;
      api('GET', '/api/unidades' + q).then(function (r) {
        desenharUnidades($('lista-unidades-busca'), $('sem-unidades-busca'), r.unidades, r.indicadaId,
          { servico: servico, cbo: cbo, especialidade: nomeEsp });
      }).catch(erro);
    });
  }

  /* ---------- agendamento ---------- */
  function ymd(d) {
    var m = String(d.getMonth() + 1).padStart(2, '0'), dia = String(d.getDate()).padStart(2, '0');
    return d.getFullYear() + '-' + m + '-' + dia;
  }

  function formatarDataHora(iso) {
    var p = iso.split('T'), d = p[0].split('-');
    return d[2] + '/' + d[1] + '/' + d[0] + ' ' + (p[1] || '').slice(0, 5);
  }

  function abrirAgendar(u, ctx) {
    estado.voltarAgendar = $('tela-unidades').hidden ? 'resultado' : 'unidades';
    estado.agendar = { unidadeId: u.id, unidadeNome: u.nome, servico: ctx.servico, cbo: ctx.cbo, especialidade: ctx.especialidade };
    estado.horarioEscolhido = null;
    $('ag-unidade').textContent = u.nome;
    $('ag-servico').textContent = ctx.servico === 'saude' ? (ctx.especialidade || 'Consulta')
      : ctx.servico === 'documento' ? 'Documentos' : 'Assistência social';
    var hoje = new Date(), max = new Date(); max.setDate(max.getDate() + 90);
    var campo = $('ag-data');
    campo.min = ymd(hoje); campo.max = ymd(max); campo.value = ymd(hoje);
    $('ag-confirmar').disabled = true;
    mostrar('agendar');
    carregarHorarios();
  }

  function carregarHorarios() {
    var a = estado.agendar; if (!a) return;
    estado.horarioEscolhido = null; $('ag-confirmar').disabled = true;
    var q = '?unidadeId=' + a.unidadeId + '&servico=' + encodeURIComponent(a.servico) + '&data=' + $('ag-data').value;
    if (a.servico === 'saude') q += '&cbo=' + encodeURIComponent(a.cbo);
    api('GET', '/api/agendamentos/horarios' + q).then(function (hs) {
      var grade = $('ag-horarios'); limpar(grade);
      $('ag-vazio').hidden = hs.length > 0;
      hs.forEach(function (h) {
        var hora = h.dataHora.split('T')[1].slice(0, 5);
        var b = el('button', {
          type: 'button', 'aria-pressed': 'false', text: hora + (h.vagas === 0 ? ' (lotado)' : ''),
          onclick: function () {
            Array.prototype.forEach.call(grade.children, function (x) { x.setAttribute('aria-pressed', 'false'); });
            b.setAttribute('aria-pressed', 'true');
            estado.horarioEscolhido = h.dataHora;
            $('ag-confirmar').disabled = false;
          }
        });
        if (h.vagas === 0) b.disabled = true;
        grade.appendChild(b);
      });
    }).catch(erro);
  }

  function iniciarAgendar() {
    $('ag-data').addEventListener('change', carregarHorarios);
    $('ag-voltar').addEventListener('click', function () { mostrar(estado.voltarAgendar || 'resultado'); });
    $('ag-confirmar').addEventListener('click', function () {
      var a = estado.agendar;
      if (!a || !estado.horarioEscolhido) return;
      var corpo = { unidadeId: a.unidadeId, servico: a.servico, dataHora: estado.horarioEscolhido };
      if (a.servico === 'saude') corpo.cbo = a.cbo;
      $('ag-confirmar').disabled = true;
      api('POST', '/api/agendamentos', corpo).then(function (r) {
        abrirAgendamentos().then(function () {
          aviso('Agendamento confirmado. Protocolo ' + r.protocolo + ' para ' + formatarDataHora(r.dataHora) + '.', true);
        });
      }).catch(function (e) { erro(e); carregarHorarios(); });
    });
  }

  function abrirAgendamentos() {
    return api('GET', '/api/agendamentos/me').then(function (lista) {
      var ul = $('lista-agendamentos'); limpar(ul);
      $('sem-agendamentos').hidden = lista.length > 0;
      lista.forEach(function (a) {
        var item = el('li', { class: a.status === 'cancelado' ? 'cancelado' : '' }, [
          el('strong', { text: a.unidadeNome }),
          el('div', { text: (a.especialidade || a.servico) + ' · ' + formatarDataHora(a.dataHora) }),
          el('div', { class: 'discreto', text: 'Protocolo ' + a.protocolo + ' · ' + a.status })
        ]);
        if (a.status === 'confirmado') {
          item.appendChild(el('button', {
            type: 'button', class: 'secundario', text: 'Cancelar',
            onclick: function () {
              api('DELETE', '/api/agendamentos/' + a.id).then(function () {
                abrirAgendamentos().then(function () { aviso('Agendamento cancelado.', true); });
              }).catch(erro);
            }
          }));
        }
        ul.appendChild(item);
      });
      mostrar('agendamentos');
    }).catch(erro);
  }

  /* ---------- painel da unidade ---------- */
  function pararPainel() { if (estado.painelTimer) { clearInterval(estado.painelTimer); estado.painelTimer = null; } }

  function linhaPainel(rotulo, valor, filho) {
    var l = el('div', { class: 'painel-linha' }, [el('span', { text: rotulo }), filho || el('strong', { text: valor })]);
    return l;
  }

  function desenharPainel(p) {
    var corpo = $('painel-corpo'); limpar(corpo);
    var grade = el('div', { class: 'painel-grade' });
    var conexao = el('strong', null, [el('span', { class: 'ponto ' + (p.online ? 'on' : 'off') }), p.online ? 'Online' : 'Sem conexão']);
    grade.appendChild(linhaPainel('Conexão', null, conexao));
    grade.appendChild(linhaPainel('Fila agora', p.filaPessoas === null || p.filaPessoas === undefined ? 'sem dado' : p.filaPessoas + ' pessoas'));
    grade.appendChild(linhaPainel('Indicações nas últimas 24 h', String(p.indicacoesUltimas24h)));
    grade.appendChild(linhaPainel('Agendamentos hoje', String(p.agendamentosHoje)));
    (p.profissionais || []).forEach(function (pr) {
      var presente = el('strong', null, [el('span', { class: 'ponto ' + (pr.presenteAgora ? 'on' : 'off') }),
        pr.presenteAgora ? 'Presente' : 'Ausente' + (pr.agendaAberta ? ' (agenda aberta)' : '')]);
      grade.appendChild(linhaPainel(pr.especialidade + ' (' + pr.qtdProfissionais + ')', null, presente));
    });
    if ((p.servicos || []).length) grade.appendChild(linhaPainel('Serviços', p.servicos.join(', ')));
    corpo.appendChild(grade);
  }

  function abrirPainel(id, nome) {
    estado.voltarPainel = $('tela-unidades').hidden ? 'resultado' : 'unidades';
    $('painel-titulo').textContent = nome;
    var atualizar = function () {
      api('GET', '/api/unidades/' + id + '/painel').then(desenharPainel).catch(erro);
    };
    mostrar('painel');
    atualizar();
    estado.painelTimer = setInterval(atualizar, 10000);
  }

  /* ---------- início ---------- */
  document.addEventListener('DOMContentLoaded', function () {
    iniciarEntrada(); iniciarTriagem(); iniciarUnidades(); iniciarAgendar();

    document.querySelectorAll('[data-ir]').forEach(function (b) {
      b.addEventListener('click', function () {
        var destino = b.getAttribute('data-ir');
        if (destino === 'agendamentos') abrirAgendamentos(); else mostrar(destino);
      });
    });
    $('btn-inicio').addEventListener('click', function () { mostrar(estado.token ? 'triagem' : 'entrada'); });
    $('btn-sair').addEventListener('click', sair);
    $('btn-copiar').addEventListener('click', copiarResumo);
    $('painel-voltar').addEventListener('click', function () { mostrar(estado.voltarPainel || 'unidades'); });

    if (estado.token) { carregarEspecialidades().then(function () { mostrar('triagem'); }); } else { mostrar('entrada'); }
  });
})();
