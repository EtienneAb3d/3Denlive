/*
 * 3Denlive StereoPlayer — pure JavaScript stereo video player.
 *
 * Plays full-width side-by-side PARALLEL videos (left eye on the left half,
 * right eye on the right half, e.g. 3840x1080 = 2 x 1920x1080) with a
 * format selector:
 *   - Anaglyph : simple red/cyan colour anaglyph, default
 *   - Dubois   : red/cyan anaglyph, Dubois least-squares method (less ghosting)
 *   - SBS      : the file as is (parallel side-by-side)
 *   - Crossed  : left and right views swapped (cross-eyed viewing)
 *
 * Usage:
 *   <div class="stereo-player" data-src="video_P.mp4"></div>
 *   <script src="stereoplayer.js"></script>
 *
 * Optional attributes: data-poster="image.jpg", data-mode="anaglyph|dubois|sbs|crossed",
 *   data-preview="image_sbs.jpg" (default: video_P.preview.jpg next to video_P.mp4).
 * Preview: if the SBS preview image exists (same layout as the video, e.g. its
 * first frame), it is displayed without touching the MP4; the video is only
 * loaded when played. If the image is missing (404), the MP4 is analysed to
 * show its first frame, as before.
 * The video must be served from the same origin (WebGL needs it untainted).
 *
 * Copy embedded by 3Denlive in the HTML pages it writes (rendering, instant images), from
 * https://3denlive.com/stereoplayer.js, with these changes:
 *   - data-type="image": a still parallel image (<img>) instead of a video, no play controls;
 *   - a page opened from the disk (file://) gives a tainted video/image that WebGL refuses:
 *     the player then falls back to the Canvas 2D renderer (no pixel read back) instead of SBS;
 *   - data-layout="sbs|crossed|mono|anaglyph": layout of the file (default sbs = parallel).
 *     A crossed file (_X) is shown in every format (its halves swapped back); a mono (_Left,
 *     _Right) or anaglyph (_A, _Dubois) file can't be recomposed: shown as is, no format selector.
 */
(function () {
  "use strict";

  var MODES = ["anaglyph", "dubois", "sbs", "crossed"];
  function isAnaglyph(m) { return m === "anaglyph" || m === "dubois"; }
  var STORE_KEY = "3De.stereoPlayer.mode";
  var PRIME_TIMEOUT = 15000; // ms max spent on one player's first frame before moving on
  var players = [];

  var lang = (window.$lng || document.documentElement.lang || navigator.language || "en")
    .toString().toLowerCase().slice(0, 2);
  var T = {
    en: { anaglyph: "Anaglyph", dubois: "Anaglyph (Dubois)", sbs: "SBS (parallel)", crossed: "Crossed",
          play: "Play", pause: "Pause", mute: "Mute", unmute: "Unmute",
          fullscreen: "Full screen", format: "Stereo format", error: "Unable to load the video." },
    fr: { anaglyph: "Anaglyphe", dubois: "Anaglyphe (Dubois)", sbs: "SBS (parallèle)", crossed: "Croisé",
          play: "Lecture", pause: "Pause", mute: "Couper le son", unmute: "Rétablir le son",
          fullscreen: "Plein écran", format: "Format stéréo", error: "Impossible de charger la vidéo." },
    de: { anaglyph: "Anaglyphe", dubois: "Anaglyphe (Dubois)", sbs: "SBS (parallel)", crossed: "Gekreuzt", play: "Wiedergabe", pause: "Pause", mute: "Ton aus", unmute: "Ton an", fullscreen: "Vollbild", format: "Stereoformat", error: "Video kann nicht geladen werden." },
    es: { anaglyph: "Anaglifo", dubois: "Anaglifo (Dubois)", sbs: "SBS (paralelo)", crossed: "Cruzado", play: "Reproducir", pause: "Pausa", mute: "Silenciar", unmute: "Activar sonido", fullscreen: "Pantalla completa", format: "Formato estéreo", error: "No se puede cargar el vídeo." },
    it: { anaglyph: "Anaglifo", dubois: "Anaglifo (Dubois)", sbs: "SBS (parallelo)", crossed: "Incrociato", play: "Riproduci", pause: "Pausa", mute: "Disattiva audio", unmute: "Attiva audio", fullscreen: "Schermo intero", format: "Formato stereo", error: "Impossibile caricare il video." },
    pt: { anaglyph: "Anáglifo", dubois: "Anáglifo (Dubois)", sbs: "SBS (paralelo)", crossed: "Cruzado", play: "Reproduzir", pause: "Pausa", mute: "Silenciar", unmute: "Ativar som", fullscreen: "Ecrã inteiro", format: "Formato estéreo", error: "Não foi possível carregar o vídeo." },
  };
  T = T[lang] || T.en;

  function loadMode() {
    try { var m = localStorage.getItem(STORE_KEY); if (MODES.indexOf(m) >= 0) return m; } catch (e) {}
    return "anaglyph";
  }
  function saveMode(m) { try { localStorage.setItem(STORE_KEY, m); } catch (e) {} }

  /* ---------------------------------------------------------------- CSS */
  var CSS = "" +
    ".sp{position:relative;width:100%;background:#000;color:#fff;font:14px Roboto,Arial,sans-serif;user-select:none;-webkit-user-select:none;line-height:1}" +
    ".sp-stage{position:relative;width:100%;aspect-ratio:var(--sp-ar,16/9);margin:auto;cursor:pointer;overflow:hidden}" +
    ".sp-stage video,.sp-stage img,.sp-stage canvas{position:absolute;inset:0;width:100%;height:100%;display:block;background:#000}" +
    ".sp-big{position:absolute;left:50%;top:50%;width:64px;height:64px;margin:-32px 0 0 -32px;border-radius:50%;background:rgba(121,44,125,.85);display:flex;align-items:center;justify-content:center;pointer-events:none;transition:opacity .2s}" +
    ".sp-big svg{width:30px;height:30px;fill:#fff;margin-left:4px}" +
    ".sp.sp-playing .sp-big{opacity:0}" +
    ".sp.sp-loading .sp-big svg{margin-left:0;animation:sp-turn 2s ease-in-out infinite}" +
    "@keyframes sp-turn{0%,40%{transform:rotate(0)}60%,100%{transform:rotate(180deg)}}" +
    ".sp-msg{position:absolute;left:0;right:0;top:45%;text-align:center;font-size:15px;pointer-events:none}" +
    ".sp-bar{display:flex;align-items:center;gap:8px;padding:6px 8px;background:#1d1d1f;flex-wrap:wrap}" +
    ".sp-bar button{background:none;border:0;padding:4px;cursor:pointer;color:#fff;display:flex;align-items:center}" +
    ".sp-bar button svg{width:20px;height:20px;fill:currentColor}" +
    ".sp-bar button:hover,.sp-bar button:focus-visible{color:#d9a6dc;outline:none}" +
    ".sp-seek{flex:1 1 120px;min-width:80px;accent-color:#b05db5;cursor:pointer}" +
    ".sp-vol{width:70px;accent-color:#b05db5;cursor:pointer}" +
    ".sp-time{font-variant-numeric:tabular-nums;white-space:nowrap;font-size:12px;color:#ddd}" +
    ".sp-mode{background:#333;color:#fff;border:1px solid #666;border-radius:4px;padding:3px 4px;font:inherit;font-size:13px;cursor:pointer}" +
    ".sp:fullscreen{display:flex;flex-direction:column;justify-content:center;width:100%;height:100%}" +
    ".sp:fullscreen .sp-stage{flex:0 1 auto;width:min(100vw,calc((100vh - 44px) * var(--sp-arn,1.778)))}" +
    ".sp:-webkit-full-screen{display:flex;flex-direction:column;justify-content:center;width:100%;height:100%}" +
    ".sp:-webkit-full-screen .sp-stage{width:min(100vw,calc((100vh - 44px) * var(--sp-arn,1.778)))}" +
    "@media (max-width:520px){.sp-vol{display:none}}";

  function injectCSS() {
    if (document.getElementById("sp-style")) return;
    var s = document.createElement("style");
    s.id = "sp-style";
    s.textContent = CSS;
    document.head.appendChild(s);
  }

  var ICON = {
    play: '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>',
    wait: '<svg viewBox="0 0 24 24"><path d="M6 2v6l4 4-4 4v6h12v-6l-4-4 4-4V2H6zm10 14.5V20H8v-3.5l4-4 4 4zm-4-5-4-4V4h8v3.5l-4 4z"/></svg>',
    pause: '<svg viewBox="0 0 24 24"><path d="M6 5h4v14H6zm8 0h4v14h-4z"/></svg>',
    vol: '<svg viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.5 4.5 0 0 0 2.5-4zM14 3.2v2.1a7 7 0 0 1 0 13.4v2.1a9 9 0 0 0 0-17.6z"/></svg>',
    mute: '<svg viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9H3zm18.6 6.2-1.4 1.4L17 13.4l-3.2 3.2-1.4-1.4 3.2-3.2-3.2-3.2 1.4-1.4 3.2 3.2 3.2-3.2 1.4 1.4-3.2 3.2z"/></svg>',
    fs: '<svg viewBox="0 0 24 24"><path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"/></svg>'
  };

  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html) e.innerHTML = html;
    return e;
  }
  function fmt(t) {
    if (!isFinite(t)) t = 0;
    var m = Math.floor(t / 60), s = Math.floor(t % 60);
    return m + ":" + (s < 10 ? "0" : "") + s;
  }
  // Size of a source: <video> or <img> (image page, preview)
  function srcW(m) { return m.videoWidth || m.naturalWidth || 0; }
  function srcH(m) { return m.videoHeight || m.naturalHeight || 0; }

  /* ------------------------------------------------------- WebGL renderer */
  var VS = "attribute vec2 p;varying vec2 uv;void main(){uv=vec2(p.x*.5+.5,.5-p.y*.5);gl_Position=vec4(p,0.,1.);}";
  // op 0: simple colour anaglyph (left -> red, right -> green+blue)
  // op 1: Dubois least-squares red/cyan anaglyph
  // op 2: halves swapped
  // sw 1: crossed source (left eye on the right half)
  var FS = "precision mediump float;varying vec2 uv;uniform sampler2D t;uniform int mode;uniform int sw;" +
    "void main(){" +
    " if(mode<2){" +
    "  float a=uv.x*.5,b=.5+uv.x*.5;" +
    "  vec3 l=texture2D(t,vec2(sw==1?b:a,uv.y)).rgb;" +
    "  vec3 r=texture2D(t,vec2(sw==1?a:b,uv.y)).rgb;" +
    "  vec3 c=mode==0?vec3(l.r,r.g,r.b):vec3(dot(l,vec3(.456,.500,.176))+dot(r,vec3(-.043,-.088,-.002))," +
    "              dot(l,vec3(-.040,-.038,-.016))+dot(r,vec3(.378,.734,-.018))," +
    "              dot(l,vec3(-.015,-.021,-.005))+dot(r,vec3(-.072,-.113,1.226)));" +
    "  gl_FragColor=vec4(clamp(c,0.,1.),1.);" +
    " }else{" +
    "  float x=uv.x<.5?uv.x+.5:uv.x-.5;" +
    "  gl_FragColor=vec4(texture2D(t,vec2(x,uv.y)).rgb,1.);" +
    " }" +
    "}";

  /* One WebGL context shared by all players: browsers cap the number of live
     contexts per page (16 in Chrome) and drop the oldest beyond that. The
     shader renders into an offscreen canvas, then each player copies the
     result into its own 2D canvas in the same task. */
  var sharedGL; // undefined: not tried yet, null: WebGL unavailable
  function getSharedGL() {
    if (sharedGL === undefined) {
      try { sharedGL = new GLRenderer(document.createElement("canvas")); } catch (e) { sharedGL = null; }
    }
    return sharedGL;
  }
  function SharedGLRenderer(canvas) {
    var ctx = canvas.getContext("2d");
    var fallback = null;
    this.draw = function (video, op, swap) {
      var shared = getSharedGL();
      if (shared && !shared.lost()) {
        // Errors (e.g. tainted file:// source) propagate to render(), which falls back to the 2D renderer.
        shared.draw(video, op, swap, canvas.width, canvas.height);
        ctx.drawImage(shared.canvas, 0, 0);
        return;
      }
      // WebGL unavailable or context lost (until restored): plain 2D rendering.
      (fallback || (fallback = new Renderer2D(canvas))).draw(video, op, swap);
    };
  }

  function GLRenderer(canvas) {
    this.canvas = canvas;
    var lost = false;
    canvas.addEventListener("webglcontextlost", function (e) { e.preventDefault(); lost = true; });
    canvas.addEventListener("webglcontextrestored", function () { sharedGL = undefined; });
    var gl = canvas.getContext("webgl", { alpha: false, antialias: false, preserveDrawingBuffer: false }) ||
             canvas.getContext("experimental-webgl", { alpha: false });
    if (!gl) throw new Error("no webgl");
    function sh(type, src) {
      var s = gl.createShader(type);
      gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
      return s;
    }
    var prog = gl.createProgram();
    gl.attachShader(prog, sh(gl.VERTEX_SHADER, VS));
    gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, FS));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error("link");
    gl.useProgram(prog);
    var buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    var loc = gl.getAttribLocation(prog, "p");
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
    var tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    var uMode = gl.getUniformLocation(prog, "mode");
    var uSw = gl.getUniformLocation(prog, "sw");
    this.lost = function () { return lost; };
    this.draw = function (video, op, swap, w, h) {
      if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
      gl.viewport(0, 0, w, h);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, gl.RGB, gl.UNSIGNED_BYTE, video);
      gl.uniform1i(uMode, op === "anaglyph" ? 0 : op === "dubois" ? 1 : 2);
      gl.uniform1i(uSw, swap ? 1 : 0);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    };
  }

  /* --------------------------------------------- Canvas 2D fallback */
  function Renderer2D(canvas) {
    var ctx = canvas.getContext("2d");
    var tmp = document.createElement("canvas");
    var tctx = tmp.getContext("2d");
    this.draw = function (video, op, swap) {
      var vw = srcW(video), vh = srcH(video), hw = vw / 2;
      var W = canvas.width, H = canvas.height;
      ctx.globalCompositeOperation = "source-over";
      if (op === "swap") {
        ctx.drawImage(video, hw, 0, hw, vh, 0, 0, W / 2, H);
        ctx.drawImage(video, 0, 0, hw, vh, W / 2, 0, W / 2, H);
        return;
      }
      // Colour anaglyph (also used for Dubois in this fallback): left -> red, right -> green+blue.
      var lx = swap ? hw : 0, rx = swap ? 0 : hw;
      if (tmp.width !== W || tmp.height !== H) { tmp.width = W; tmp.height = H; }
      ctx.drawImage(video, lx, 0, hw, vh, 0, 0, W, H);
      ctx.globalCompositeOperation = "multiply";
      ctx.fillStyle = "#f00"; ctx.fillRect(0, 0, W, H);
      tctx.globalCompositeOperation = "source-over";
      tctx.drawImage(video, rx, 0, hw, vh, 0, 0, W, H);
      tctx.globalCompositeOperation = "multiply";
      tctx.fillStyle = "#0ff"; tctx.fillRect(0, 0, W, H);
      ctx.globalCompositeOperation = "lighter";
      ctx.drawImage(tmp, 0, 0);
      ctx.globalCompositeOperation = "source-over";
    };
  }

  /* ---------------------------------------------------------- Player */
  function Player(host) {
    var self = this;
    var src = host.getAttribute("data-src");
    var isImage = host.getAttribute("data-type") === "image";
    // Layout of the file: parallel (default), crossed, or flat (mono / anaglyph: shown as is)
    var srcLayout = host.getAttribute("data-layout") || "sbs";
    var flat = srcLayout === "mono" || srcLayout === "anaglyph";
    var crossedSrc = srcLayout === "crossed";
    // Format shown by the <video> / <img> itself, without the canvas
    var rawMode = crossedSrc ? "crossed" : "sbs";
    var previewUrl = host.getAttribute("data-preview") || src.replace(/\.[^.\/]*$/, "") + ".preview.jpg";
    var img = null; // preview image (same layout as the video), once loaded
    var mode = MODES.indexOf(host.getAttribute("data-mode")) >= 0 ? host.getAttribute("data-mode") : loadMode();

    host.innerHTML = "";
    host.classList.add("sp");
    var stage = el("div", "sp-stage");
    var video;
    if (isImage) {
      video = el("img");
      video.alt = "";
    } else {
      video = el("video");
      // No src yet: the video is attached by start(), either when the loading
      // queue reaches this player or when the user plays it.
      video.preload = "none";
      video.playsInline = true;
      video.setAttribute("playsinline", "");
      video.setAttribute("webkit-playsinline", "");
      if (host.getAttribute("data-poster")) video.poster = host.getAttribute("data-poster");
    }
    var canvas = el("canvas");
    // Hourglass until the first frame is shown (see primeDone), then Play.
    var big = el("div", "sp-big", ICON.wait);
    host.classList.add("sp-loading");
    var msg = el("div", "sp-msg");
    stage.appendChild(video); stage.appendChild(canvas); if (!isImage) stage.appendChild(big); stage.appendChild(msg);

    var bar = el("div", "sp-bar");
    var bPlay = el("button", "", ICON.play); bPlay.type = "button"; bPlay.title = T.play;
    var time = el("span", "sp-time"); time.textContent = "0:00 / 0:00";
    var seek = el("input", "sp-seek"); seek.type = "range"; seek.min = 0; seek.max = 1000; seek.value = 0; seek.step = 1;
    seek.setAttribute("aria-label", "Position");
    var bMute = el("button", "", ICON.vol); bMute.type = "button"; bMute.title = T.mute;
    var vol = el("input", "sp-vol"); vol.type = "range"; vol.min = 0; vol.max = 1; vol.step = 0.05; vol.value = 1;
    vol.setAttribute("aria-label", "Volume");
    var sel = el("select", "sp-mode"); sel.title = T.format; sel.setAttribute("aria-label", T.format);
    MODES.forEach(function (m) { var o = el("option"); o.value = m; o.textContent = T[m]; sel.appendChild(o); });
    var bFs = el("button", "", ICON.fs); bFs.type = "button"; bFs.title = T.fullscreen;
    var items = isImage ? [sel, bFs] : [bPlay, time, seek, bMute, vol, sel, bFs];
    if (flat) items = items.filter(function (n) { return n !== sel; });
    items.forEach(function (n) { bar.appendChild(n); });
    if (isImage) { var gap = el("span"); gap.style.flex = "1"; bar.insertBefore(gap, bar.firstChild); stage.style.cursor = "default"; }
    host.appendChild(stage); host.appendChild(bar);

    var renderer = new SharedGLRenderer(canvas), is2D = false;

    var frameReady = false, loopOn = false;

    /* Sequential loading: start() attaches the source; prime(cb) starts it and
       calls cb once the first frame is shown (or on error / timeout), so the
       queue can move on to the next player. */
    var started = false, primed = false, firstSeek = false, onPrimed = null, primeTimer = 0;
    function start() {
      if (started) return;
      started = true;
      if (!isImage) video.preload = "metadata";
      video.src = src;
    }
    function primeDone() {
      if (primed) return;
      primed = true;
      clearTimeout(primeTimer);
      host.classList.remove("sp-loading");
      big.innerHTML = ICON.play;
      if (onPrimed) { var cb = onPrimed; onPrimed = null; cb(); }
    }
    this.prime = function (cb) {
      if (primed) { cb(); return; }
      onPrimed = cb;
      if (isImage) { start(); primeTimer = setTimeout(primeDone, PRIME_TIMEOUT); return; }
      // Try the preview image first: no need to analyse the MP4.
      var im = new Image();
      im.onload = function () {
        img = im;
        if (!host.getAttribute("data-poster")) video.poster = previewUrl; // raw format (SBS...)
        layout();
        primeDone();
      };
      im.onerror = function () {
        // No preview (404...): load the MP4 to show its first frame.
        if (primed) return;
        start();
        // Do not block the queue forever (slow network, iOS ignoring preload...).
        primeTimer = setTimeout(primeDone, PRIME_TIMEOUT);
      };
      im.src = previewUrl;
    };

    // What to draw: the video once it has a frame, else the preview image.
    function source() {
      if (isImage) return frameReady && video.complete && video.naturalWidth > 0 ? video : null;
      return frameReady && video.readyState >= 2 ? video : img;
    }
    // Shown by the <video> / <img> itself
    function isRaw() { return flat || mode === rawMode; }

    function eyeAspect() {
      var w = srcW(video) || (img && img.naturalWidth) || 3840,
          h = srcH(video) || (img && img.naturalHeight) || 1080;
      return !flat && isAnaglyph(mode) ? (w / 2) / h : w / h;
    }
    function layout() {
      var ar = eyeAspect();
      stage.style.setProperty("--sp-ar", String(ar));
      stage.style.setProperty("--sp-arn", String(ar));
      host.style.setProperty("--sp-arn", String(ar));
      var raw = isRaw();
      video.style.visibility = raw ? "visible" : "hidden";
      canvas.style.display = raw ? "none" : "block";
      var s = source();
      if (!raw && s) {
        // Backing store: native resolution, capped to displayed size * devicePixelRatio.
        var sw = srcW(s);
        var nativeW = isAnaglyph(mode) ? sw / 2 : sw;
        var dispW = Math.max(1, stage.clientWidth * (window.devicePixelRatio || 1));
        var w = Math.round(Math.min(nativeW, dispW));
        var h = Math.round(w / ar);
        if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
      }
      render();
    }
    function render() {
      var s = source();
      if (isRaw() || !s) return;
      // Anaglyphs: the eyes, wherever they are in the file; SBS / crossed: the halves swapped
      var op = isAnaglyph(mode) ? mode : "swap";
      try { renderer.draw(s, op, crossedSrc); } catch (e) {
        if (!is2D) {
          // e.g. file:// source refused by WebGL: the 2D canvas can still draw it
          try { renderer = new Renderer2D(canvas); is2D = true; renderer.draw(s, op, crossedSrc); return; } catch (e2) {}
        }
        // Last resort: the file as is.
        msg.textContent = "";
        setMode(rawMode, true);
      }
    }
    function loop() {
      if (!loopOn) return;
      render();
      if (video.requestVideoFrameCallback) video.requestVideoFrameCallback(loop);
      else requestAnimationFrame(loop);
    }
    function startLoop() { if (!loopOn) { loopOn = true; loop(); } }
    function stopLoop() { loopOn = false; }

    function setMode(m, silent) {
      mode = m;
      sel.value = m;
      layout();
      if (!silent) {
        saveMode(m);
        players.forEach(function (p) { if (p !== self) p.setMode(m, true); });
      }
    }
    this.setMode = setMode;
    this.pause = function () { if (!isImage) video.pause(); };

    function togglePlay() {
      if (isImage) return;
      start(); // user wants this one now, even if the queue has not reached it
      if (video.paused || video.ended) {
        players.forEach(function (p) { if (p !== self) p.pause(); });
        var pr = video.play();
        if (pr && pr.catch) pr.catch(function () {});
      } else video.pause();
    }
    function updTime() {
      time.textContent = fmt(video.currentTime) + " / " + fmt(video.duration);
      if (!seeking && video.duration) seek.value = Math.round(1000 * video.currentTime / video.duration);
    }

    if (isImage) {
      video.addEventListener("load", function () { frameReady = true; layout(); primeDone(); });
      video.addEventListener("error", function () { primeDone(); });
    } else {
      video.addEventListener("loadedmetadata", function () {
        layout(); updTime();
        // Show the first frame without downloading the whole file.
        if (video.paused && video.currentTime === 0) { try { video.currentTime = 0.04; firstSeek = true; } catch (e) {} }
      });
      video.addEventListener("loadeddata", function () { frameReady = true; render(); if (!firstSeek) primeDone(); });
      video.addEventListener("seeked", function () { frameReady = true; render(); primeDone(); });
      video.addEventListener("timeupdate", updTime);
      video.addEventListener("durationchange", updTime);
      video.addEventListener("play", function () {
        host.classList.add("sp-playing"); bPlay.innerHTML = ICON.pause; bPlay.title = T.pause; startLoop();
      });
      video.addEventListener("pause", function () {
        host.classList.remove("sp-playing"); bPlay.innerHTML = ICON.play; bPlay.title = T.play; stopLoop(); render();
      });
      video.addEventListener("ended", function () { stopLoop(); });
      video.addEventListener("volumechange", function () {
        var m = video.muted || video.volume === 0;
        bMute.innerHTML = m ? ICON.mute : ICON.vol;
        bMute.title = m ? T.unmute : T.mute;
        vol.value = video.muted ? 0 : video.volume;
      });
      video.addEventListener("error", function () { msg.textContent = T.error; primeDone(); });
    }

    stage.addEventListener("click", togglePlay);
    bPlay.addEventListener("click", togglePlay);
    var seeking = false;
    seek.addEventListener("input", function () {
      seeking = true;
      start();
      if (video.duration) video.currentTime = video.duration * seek.value / 1000;
    });
    seek.addEventListener("change", function () { seeking = false; });
    bMute.addEventListener("click", function () {
      if (video.muted || video.volume === 0) { video.muted = false; if (video.volume === 0) video.volume = 1; }
      else video.muted = true;
    });
    vol.addEventListener("input", function () { video.volume = +vol.value; video.muted = +vol.value === 0; });
    sel.addEventListener("change", function () { setMode(sel.value); });
    bFs.addEventListener("click", function () {
      var fsEl = document.fullscreenElement || document.webkitFullscreenElement;
      if (fsEl) { (document.exitFullscreen || document.webkitExitFullscreen).call(document); return; }
      if (host.requestFullscreen) host.requestFullscreen();
      else if (host.webkitRequestFullscreen) host.webkitRequestFullscreen();
      else if (video.webkitEnterFullscreen) video.webkitEnterFullscreen(); // iPhone: raw file only
    });
    host.addEventListener("keydown", function (e) {
      if (e.target === sel || e.target === seek || e.target === vol) return;
      if (e.key === " " || e.key === "k") { e.preventDefault(); togglePlay(); }
    });
    host.tabIndex = 0;

    if (window.ResizeObserver) new ResizeObserver(layout).observe(stage);
    else window.addEventListener("resize", layout);
    document.addEventListener("fullscreenchange", layout);
    document.addEventListener("webkitfullscreenchange", layout);

    setMode(mode, true);
  }

  /* Loading queue: players are primed one at a time, in page order. */
  var queue = [], busy = false;
  function pump() {
    if (busy || !queue.length) return;
    busy = true;
    queue.shift().prime(function () { busy = false; pump(); });
  }

  function init() {
    injectCSS();
    var nodes = document.querySelectorAll(".stereo-player[data-src]");
    for (var i = 0; i < nodes.length; i++) {
      if (nodes[i].__sp) continue;
      nodes[i].__sp = new Player(nodes[i]);
      players.push(nodes[i].__sp);
      queue.push(nodes[i].__sp);
    }
    pump();
  }
  window.StereoPlayer = { init: init };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();
