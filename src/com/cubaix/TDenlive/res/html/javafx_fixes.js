/*
 * Run by GUI/FxBrowser on each page loaded in the WebKit of JavaFX (Linux without WebKitGTK), to work
 * around what it lacks for the stereo player of the web site (stereoplayer.js). ES5, run once per page.
 *
 * 1. A video moved while paused (the player shows its first frame with currentTime = 0.04) stays stuck
 *    at that time when it plays: one more seek to the same time when it starts playing unblocks it.
 * 2. No WebGL: the player uses its Canvas 2D fallback, but the canvas of JavaFX has no blend modes
 *    ("multiply" acts as "source-over") and draws "lighter" + drawImage(canvas) wrong on screen, so the
 *    anaglyph was white, then red only. A "multiply" fillRect of a plain color is done on the pixels
 *    (getImageData / putImageData, which are drawn right), and the "lighter" drawImage of a canvas of the
 *    same size at 0,0 adds the pixels; the pixels of the last "multiply" are reused (no new read).
 * 3. requestAnimationFrame at 30 frames per second (instead of 60): the pixel work costs about 10 ms
 *    per frame, and the videos have 24 to 30.
 * 4. A click on a link to a zip is sent to the application (alert "TDE-ZIP:<url>"): JavaFX does not
 *    download files, and the demo projects are opened by the application.
 */
(function () {
	if (window.__tdeFx) { return; }
	window.__tdeFx = true;

	document.addEventListener("playing", function (e) {
		var v = e.target;
		if (v && !v.__tdeSeek) {
			v.__tdeSeek = true;
			try { v.currentTime = v.currentTime; } catch (x) {}
		}
	}, true);
	document.addEventListener("pause", function (e) {
		if (e.target) { e.target.__tdeSeek = false; }
	}, true);

	window.requestAnimationFrame = function (f) {
		return setTimeout(function () { f(Date.now()); }, 33);
	};
	window.cancelAnimationFrame = function (i) { clearTimeout(i); };

	var P = CanvasRenderingContext2D.prototype;
	var fillRect = P.fillRect, drawImage = P.drawImage, putImageData = P.putImageData, clearRect = P.clearRect;
	P.fillRect = function (x, y, w, h) {
		var s = this.fillStyle;
		if (this.globalCompositeOperation !== "multiply" || typeof s !== "string" || !/^#[0-9a-f]{6}$/i.test(s)) {
			this.__tdeIm = null;
			return fillRect.apply(this, arguments);
		}
		var f = [parseInt(s.substr(1, 2), 16) / 255, parseInt(s.substr(3, 2), 16) / 255, parseInt(s.substr(5, 2), 16) / 255];
		var W = this.canvas.width, H = this.canvas.height;
		var x0 = Math.max(0, Math.floor(x)), y0 = Math.max(0, Math.floor(y));
		var x1 = Math.min(W, Math.ceil(x + w)), y1 = Math.min(H, Math.ceil(y + h));
		if (x1 <= x0 || y1 <= y0) { return; }
		var im = this.getImageData(0, 0, W, H), d = im.data, i, c, yy, e;
		for (c = 0; c < 3; c++) {
			if (f[c] === 1) { continue; }
			for (yy = y0; yy < y1; yy++) {
				e = (yy * W + x1) * 4;
				if (f[c] === 0) { for (i = (yy * W + x0) * 4 + c; i < e; i += 4) { d[i] = 0; } }
				else { for (i = (yy * W + x0) * 4 + c; i < e; i += 4) { d[i] *= f[c]; } }
			}
		}
		putImageData.call(this, im, 0, 0);
		this.__tdeIm = im;
	};
	P.drawImage = function (src) {
		if (this.globalCompositeOperation === "lighter" && arguments.length === 3 && arguments[1] === 0 && arguments[2] === 0
				&& src && src.tagName === "CANVAS" && src.width === this.canvas.width && src.height === this.canvas.height) {
			var W = this.canvas.width, H = this.canvas.height, sc = src.getContext("2d");
			var a = this.__tdeIm || this.getImageData(0, 0, W, H), b = sc.__tdeIm || sc.getImageData(0, 0, W, H);
			var ad = a.data, bd = b.data, n = ad.length;
			for (var i = 0; i < n; i += 4) { ad[i] += bd[i]; ad[i + 1] += bd[i + 1]; ad[i + 2] += bd[i + 2]; }
			putImageData.call(this, a, 0, 0);
			this.__tdeIm = null;
			return;
		}
		this.__tdeIm = null;
		return drawImage.apply(this, arguments);
	};
	P.putImageData = function () { this.__tdeIm = null; return putImageData.apply(this, arguments); };
	P.clearRect = function () { this.__tdeIm = null; return clearRect.apply(this, arguments); };

	document.addEventListener("click", function (e) {
		var a = e.target;
		while (a && a.tagName !== "A") { a = a.parentNode; }
		if (a && /\.zip([?#].*)?$/i.test(a.href || "")) {
			e.preventDefault();
			alert("TDE-ZIP:" + a.href);
		}
	}, true);
})();
