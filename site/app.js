// AETHER's site. No libraries and nothing fetched from anyone else: this page is the app's own world.

const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches
const coarse = matchMedia('(pointer: coarse)').matches
const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x))
const ramp = (x, a, b) => clamp((x - a) / (b - a))

// --- Motion is springs: Apple's response and damping, the app's own Fluid.kt ------------------------

class Spring {
  constructor(value = 0, response = 0.5, damping = 1) {
    this.x = value
    this.v = 0
    this.target = value
    this.omega = (2 * Math.PI) / response
    this.damping = damping
  }
  step(dt) {
    const k = this.omega * this.omega
    const c = 2 * this.damping * this.omega
    let left = Math.min(dt, 1 / 30)
    while (left > 0) {
      const h = Math.min(left, 1 / 240)
      this.v += (-k * (this.x - this.target) - c * this.v) * h
      this.x += this.v * h
      left -= h
    }
    return this
  }
  get settled() { return Math.abs(this.x - this.target) < 0.0008 && Math.abs(this.v) < 0.001 }
}

// CSS transitions get the same physics, as a linear() curve.
function springCurve(response, damping) {
  const s = new Spring(0, response, damping)
  s.target = 1
  const dt = 1 / 120
  const points = []
  let t = 0
  while (t < 6) {
    s.step(dt)
    t += dt
    points.push(s.x)
    if (s.settled && t > response * 0.5) break
  }
  const count = 44
  const curve = Array.from({ length: count + 1 }, (_, i) => points[Math.round((i / count) * (points.length - 1))].toFixed(4))
  curve[count] = '1'
  return { curve: `linear(${curve.join(',')})`, seconds: t }
}
if (CSS.supports('transition-timing-function', 'linear(0, 1)')) {
  const { curve, seconds } = springCurve(0.62, 0.92)
  document.documentElement.style.setProperty('--spring', curve)
  document.documentElement.style.setProperty('--spring-time', `${seconds.toFixed(2)}s`)
}

// One clock for everything that moves. Nothing runs while the tab can't be seen.
const tickers = new Set()
let last = 0
function frame(now) {
  const dt = Math.min((now - last) / 1000, 0.05)
  last = now
  for (const t of tickers) t(dt, now / 1000)
  requestAnimationFrame(frame)
}
requestAnimationFrame((now) => { last = now; frame(now) })

// The film grain, made here so that it's one small tile and no download.
{
  const c = document.createElement('canvas')
  c.width = c.height = 220
  const g = c.getContext('2d')
  const img = g.createImageData(220, 220)
  for (let i = 0; i < img.data.length; i += 4) {
    const v = Math.random() * 255
    img.data[i] = img.data[i + 1] = img.data[i + 2] = v
    img.data[i + 3] = 255
  }
  g.putImageData(img, 0, 0)
  document.querySelector('.grain').style.backgroundImage = `url(${c.toDataURL('image/png')})`
}

// --- The statues ------------------------------------------------------------------------------------

const FIGURES = [
  ['statue', 'The Listener', 'Made for AETHER, MMXXVI'],
  ['zeus', 'Zeus', 'Bronze from Cape Artemision, c. 460 BC'],
  ['hera', 'Hera', 'The Barberini type'],
  ['apollo', 'Apollo', 'The Belvedere, after Leochares'],
  ['nike', 'Nike', 'Samothrace, c. 190 BC'],
  ['smyrna', 'Zeus', 'From Smyrna, the Louvre'],
  ['artemis', 'Artemis', 'Versailles, after Leochares'],
  ['poseidon', 'Poseidon', 'Parian marble from Melos'],
  ['athena', 'Athena', 'The Giustiniani, the Vatican'],
  ['hermes', 'Hermes', 'Olympia, with the infant Dionysus'],
  ['aphrodite', 'Aphrodite', 'Milo, c. 100 BC'],
  ['dionysus', 'Dionysus', 'The Richelieu, the Louvre'],
]
const NUMERALS = ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X', 'XI', 'XII']

const VERTEX = `
attribute vec2 a_from, a_to, a_size, a_mix;
uniform float u_t, u_unit, u_rise, u_time, u_hover;
uniform vec2 u_res, u_mouse;
varying float v_r, v_half, v_alpha;
void main() {
  float u = clamp((u_t / 0.94 - a_mix.x * 0.46) / 0.54, 0.0, 1.0);
  float e = u * u * (3.0 - 2.0 * u);
  float lift = sin(3.14159 * e);
  vec2 way = a_to - a_from;
  vec2 c = mix(a_from, a_to, e) + vec2(-way.y, way.x) * lift * (a_mix.y - 0.5) * 0.4;
  c += vec2(sin(c.y / (62.0 * u_unit) + a_mix.y * 6.283 + u_t * 5.0), cos(c.x / (54.0 * u_unit) + a_mix.y * 4.0 + u_t * 4.0)) * lift * 11.0 * u_unit;
  float r = mix(a_size.x, a_size.y, e) * (1.0 - 0.38 * lift);

  // A slow light moving down the figure.
  r *= 1.0 + 0.07 * sin(c.y / (120.0 * u_unit) - u_time * 0.45 + c.x / (300.0 * u_unit));

  // The dots part round the pointer, as water round a finger.
  vec2 d = c - u_mouse;
  float reach = 92.0 * u_unit;
  float push = u_hover * exp(-dot(d, d) / (2.0 * reach * reach));
  c += normalize(d + 0.001) * push * 30.0 * u_unit;
  r *= 1.0 - push * 0.35;

  // Scrolled away, the figure goes up into the air it's named for.
  float rate = 0.35 + a_mix.y * 0.65;
  c.y -= u_rise * u_rise * u_res.y * 1.15 * rate;
  c.x += sin(a_mix.y * 40.0 + c.y / (90.0 * u_unit)) * u_rise * 70.0 * u_unit;
  r *= 1.0 - u_rise * 0.55;
  v_alpha = 1.0 - smoothstep(0.5, 1.0, u_rise * (0.65 + a_mix.y * 0.55));

  v_r = r;
  v_half = r + 1.5;
  gl_PointSize = v_half * 2.0;
  gl_Position = vec4(c.x / u_res.x * 2.0 - 1.0, 1.0 - c.y / u_res.y * 2.0, 0.0, 1.0);
}`

const FRAGMENT = `
precision mediump float;
varying float v_r, v_half, v_alpha;
void main() {
  float d = length(gl_PointCoord - 0.5) * 2.0 * v_half;
  float ink = (1.0 - smoothstep(v_r - 0.75, v_r + 0.75, d)) * v_alpha;
  gl_FragColor = vec4(ink);
}`

const CURVE_SIDE = 1024
function along(x0, y0) {
  let x = x0, y = y0, d = 0
  for (let s = CURVE_SIDE >> 1; s > 0; s >>= 1) {
    const rx = (x & s) ? 1 : 0
    const ry = (y & s) ? 1 : 0
    d += s * s * ((3 * rx) ^ ry)
    if (!ry) {
      if (rx) { x = CURVE_SIDE - 1 - x; y = CURVE_SIDE - 1 - y }
      const t = x; x = y; y = t
    }
  }
  return d
}

// Dark to bright in equal shares, each share along the curve: nearby dots go to nearby places.
function ranked(tones, curve) {
  const n = tones.length
  const byTone = Array.from({ length: n }, (_, i) => i).sort((a, b) => tones[a] - tones[b] || a - b)
  const out = new Int32Array(n)
  const SHARES = 24
  let start = 0
  for (let share = 1; share <= SHARES; share++) {
    const end = Math.floor((share * n) / SHARES)
    byTone.slice(start, end).sort((a, b) => curve[a] - curve[b]).forEach((cell, k) => { out[start + k] = cell })
    start = end
  }
  return out
}

class Dots {
  constructor(canvas) {
    this.canvas = canvas
    const gl = canvas.getContext('webgl', { alpha: false, antialias: false, powerPreference: 'high-performance' })
    if (!gl) throw new Error('no webgl')
    this.gl = gl
    const compile = (type, src) => {
      const s = gl.createShader(type)
      gl.shaderSource(s, src)
      gl.compileShader(s)
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s))
      return s
    }
    const p = gl.createProgram()
    gl.attachShader(p, compile(gl.VERTEX_SHADER, VERTEX))
    gl.attachShader(p, compile(gl.FRAGMENT_SHADER, FRAGMENT))
    gl.linkProgram(p)
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p))
    gl.useProgram(p)
    this.u = {}
    for (const name of ['u_t', 'u_unit', 'u_rise', 'u_time', 'u_hover', 'u_res', 'u_mouse']) this.u[name] = gl.getUniformLocation(p, name)
    this.a = {}
    for (const name of ['a_from', 'a_to', 'a_size', 'a_mix']) { this.a[name] = gl.getAttribLocation(p, name); gl.enableVertexAttribArray(this.a[name]) }
    this.buffer = gl.createBuffer()
    gl.bindBuffer(gl.ARRAY_BUFFER, this.buffer)
    gl.enable(gl.BLEND)
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA)
    gl.clearColor(0, 0, 0, 1)
    this.count = 0
    this.t = new Spring(0, 3.6, 1)
    this.rise = 0
    this.hover = new Spring(0, 0.5, 1)
    this.mx = new Spring(0, 0.28, 1)
    this.my = new Spring(0, 0.28, 1)
    this.prints = new Map()
    this.shown = null
    this.next = null
  }

  // The lattice is the app's: the halftone screen at its angle.
  layout() {
    const dpr = Math.min(devicePixelRatio || 1, 2)
    const rect = this.canvas.getBoundingClientRect()
    this.w = Math.max(1, Math.round(rect.width * dpr))
    this.h = Math.max(1, Math.round(rect.height * dpr))
    this.canvas.width = this.w
    this.canvas.height = this.h
    this.gl.viewport(0, 0, this.w, this.h)
    this.unit = dpr
    // The figure stands in a niche the height of the window, or wider on a phone.
    this.fh = Math.max(this.h, this.w * 1.5 * (this.w < this.h ? 1.05 : 0))
    this.fw = (this.fh * 2) / 3
    this.fx = (this.w - this.fw) / 2
    this.fy = this.h - this.fh
    this.pitch = Math.max(this.fh / (coarse ? 170 : 215), 3.2 * dpr)
    const s = Math.sin(0.6), k = Math.cos(0.6), pitch = this.pitch
    const xs = [0, this.w * k, -this.h * s, this.w * k - this.h * s]
    const ys = [0, this.w * s, this.h * k, this.w * s + this.h * k]
    const cells = { x: [], y: [], curve: [], seed: [] }
    for (let i = Math.floor(Math.min(...xs) / pitch); i <= Math.ceil(Math.max(...xs) / pitch); i++) {
      for (let j = Math.floor(Math.min(...ys) / pitch); j <= Math.ceil(Math.max(...ys) / pitch); j++) {
        const rx = (i + 0.5) * pitch, ry = (j + 0.5) * pitch
        const x = k * rx + s * ry, y = -s * rx + k * ry
        if (x < this.fx - pitch || x > this.fx + this.fw + pitch || y < -pitch || y > this.h + pitch) continue
        cells.x.push(x)
        cells.y.push(y)
        cells.curve.push(along(Math.min(CURVE_SIDE - 1, Math.max(0, Math.floor(((x - this.fx) / this.fw) * (CURVE_SIDE - 1)))), Math.min(CURVE_SIDE - 1, Math.max(0, Math.floor((y / this.h) * (CURVE_SIDE - 1))))))
        const h = (Math.imul(i, 73856093) ^ Math.imul(j, 19349663)) >>> 0
        cells.seed.push(((h ^ (h >>> 13)) & 255) / 255)
      }
    }
    this.cells = cells
    this.toneCache = new Map()
  }

  async load(name) {
    if (this.prints.has(name)) return this.prints.get(name)
    const img = new Image()
    img.src = `assets/statues/${name}.jpg`
    await img.decode()
    const c = document.createElement('canvas')
    c.width = img.naturalWidth
    c.height = img.naturalHeight
    const g = c.getContext('2d', { willReadFrequently: true })
    g.drawImage(img, 0, 0)
    const print = { w: c.width, h: c.height, data: g.getImageData(0, 0, c.width, c.height).data }
    this.prints.set(name, print)
    return print
  }

  tones(name) {
    if (this.toneCache.has(name)) return this.toneCache.get(name)
    const p = this.prints.get(name)
    const { x, y } = this.cells
    const out = new Float32Array(x.length)
    for (let i = 0; i < x.length; i++) {
      const u = (x[i] - this.fx) / this.fw, v = (y[i] - this.fy) / this.fh
      const px = Math.floor(clamp(u, 0, 0.9999) * p.w), py = Math.floor(clamp(v, 0, 0.9999) * p.h)
      const l = p.data[(py * p.w + px) * 4] / 255
      let tone = clamp((l - 0.5) * 1.45 + 0.5)
      // The niche's edges and foot go to black, so the figure stands out of the wall.
      const ex = (u - 0.5) / 0.56, ey = (v - 0.36) / 0.72
      tone *= 1 - ramp(Math.sqrt(ex * ex + ey * ey), 0.6, 1)
      tone *= 1 - 0.97 * ramp(y[i] / this.h, 0.48, 0.88)
      out[i] = tone
    }
    this.toneCache.set(name, out)
    return out
  }

  // From scattered points of light, or from one statue, to the next.
  prepare(fromName, toName) {
    const { x, y, curve, seed } = this.cells
    const n = x.length
    const reach = this.pitch * 0.66
    const b = this.tones(toName)
    const stars = !fromName
    const a = stars ? null : this.tones(fromName)
    const orderA = stars ? null : ranked(a, curve)
    const orderB = ranked(b, curve)
    const v = new Float32Array(n * 8)
    let w = 0
    for (let q = 0; q < n; q++) {
      const g = orderB[q]
      const c = stars ? q : orderA[q]
      const ra = stars ? 0.5 + seed[q] * this.unit * 0.8 : Math.sqrt(a[c]) * reach
      const rb = Math.sqrt(b[g]) * reach
      if (Math.max(ra, rb) < 0.35 || (stars && rb < 0.35)) continue
      const fromX = stars ? Math.random() * this.w : x[c]
      const fromY = stars ? Math.random() * this.h : y[c]
      v[w++] = fromX
      v[w++] = fromY
      v[w++] = x[g]
      v[w++] = y[g]
      v[w++] = ra
      v[w++] = rb
      v[w++] = clamp(0.72 * (1 - fromY / this.h) + 0.28 * seed[stars ? q : c])
      v[w++] = seed[stars ? q : c]
    }
    const gl = this.gl
    gl.bufferData(gl.ARRAY_BUFFER, v.subarray(0, w), gl.DYNAMIC_DRAW)
    const stride = 32
    gl.vertexAttribPointer(this.a.a_from, 2, gl.FLOAT, false, stride, 0)
    gl.vertexAttribPointer(this.a.a_to, 2, gl.FLOAT, false, stride, 8)
    gl.vertexAttribPointer(this.a.a_size, 2, gl.FLOAT, false, stride, 16)
    gl.vertexAttribPointer(this.a.a_mix, 2, gl.FLOAT, false, stride, 24)
    this.count = w / 8
  }

  draw(time) {
    const { gl, u } = this
    gl.uniform1f(u.u_t, this.t.x)
    gl.uniform1f(u.u_unit, this.unit)
    gl.uniform1f(u.u_rise, this.rise)
    gl.uniform1f(u.u_time, time)
    gl.uniform1f(u.u_hover, this.hover.x)
    gl.uniform2f(u.u_res, this.w, this.h)
    gl.uniform2f(u.u_mouse, this.mx.x, this.my.x)
    gl.clear(gl.COLOR_BUFFER_BIT)
    gl.drawArrays(gl.POINTS, 0, this.count)
  }
}

const HOLD = 7.5
const veil = document.querySelector('.veil')
const stage = document.querySelector('.hero__stage')
const hero = document.querySelector('.hero')
const title = document.querySelector('.hero__title')
const parts = { fig: document.getElementById('fig'), name: document.getElementById('name'), note: document.getElementById('note'), count: document.getElementById('count') }

function caption(i) {
  const [, name, note] = FIGURES[i]
  const set = (el, text) => {
    const mask = el.parentElement
    mask.classList.add('out')
    setTimeout(() => { el.textContent = text; mask.classList.remove('out') }, 460)
  }
  set(parts.fig, `Fig. ${NUMERALS[i]}`)
  set(parts.name, name)
  set(parts.note, note)
  set(parts.count, NUMERALS[i])
}

async function start() {
  let dots
  try {
    dots = new Dots(document.getElementById('dots'))
  } catch (e) {
    console.warn('AETHER: the dots need WebGL', e)
    document.documentElement.classList.add('no-gl')
    lift()
    return
  }
  dots.layout()
  await Promise.all([dots.load(FIGURES[0][0]), document.fonts.load('100px "Pinyon Script"'), document.fonts.ready])
  dots.prepare(null, FIGURES[0][0])
  dots.shown = 0
  dots.t.target = 1
  if (reduce) { dots.t.x = 1; dots.t.v = 0 }

  // The rest come in behind the first, one at a time, so nothing waits on them.
  ;(async () => { for (const [name] of FIGURES.slice(1)) await dots.load(name).catch(() => {}) })()

  setTimeout(lift, reduce ? 0 : 900)

  let visible = true
  new IntersectionObserver(([e]) => { visible = e.isIntersecting }).observe(stage)

  let hold = 0
  let changing = true
  let captioned = true
  let pending = null

  function advance() {
    const next = (dots.shown + 1) % FIGURES.length
    if (!dots.prints.has(FIGURES[next][0])) { hold = HOLD - 1; return }
    dots.prepare(FIGURES[dots.shown][0], FIGURES[next][0])
    dots.t.x = 0
    dots.t.v = 0
    dots.t.target = 1
    changing = true
    captioned = false
    pending = next
  }

  if (!coarse) {
    stage.addEventListener('pointermove', (e) => {
      const r = stage.getBoundingClientRect()
      const k = dots.unit
      const x = (e.clientX - r.left) * k, y = (e.clientY - r.top) * k
      if (dots.hover.target === 0) { dots.mx.x = x; dots.my.x = y }
      dots.mx.target = x
      dots.my.target = y
      dots.hover.target = 1
    })
    stage.addEventListener('pointerleave', () => { dots.hover.target = 0 })
  }

  let resizeTimer
  new ResizeObserver(() => {
    clearTimeout(resizeTimer)
    resizeTimer = setTimeout(() => {
      dots.layout()
      const name = FIGURES[pending ?? dots.shown][0]
      dots.prepare(name, name)
      dots.t.x = 1
      dots.t.v = 0
      dots.t.target = 1
      if (pending != null) { dots.shown = pending; pending = null; if (!captioned) { caption(dots.shown); captioned = true } }
      changing = false
      hold = 0
    }, 160)
  }).observe(stage)

  tickers.add((dt, time) => {
    const scrolled = clamp((scrollY) / Math.max(1, hero.offsetHeight - innerHeight))
    const rise = reduce ? 0 : ramp(scrolled, 0.04, 0.96)
    dots.rise = rise
    title.style.transform = `translate3d(0, ${(-rise * 22).toFixed(2)}vh, 0)`
    title.style.opacity = (1 - ramp(rise, 0.05, 0.55)).toFixed(3)
    document.querySelectorAll('.label, .hero__hint').forEach((el) => { el.style.visibility = rise > 0.3 ? 'hidden' : '' })
    if (!visible || document.hidden) return
    dots.t.step(dt)
    dots.hover.step(dt)
    dots.mx.step(dt)
    dots.my.step(dt)
    dots.draw(time)
    if (changing) {
      if (!captioned && dots.t.x > 0.32) { caption(pending); captioned = true }
      if (dots.t.x > 0.999) {
        changing = false
        hold = 0
        if (pending != null) { dots.shown = pending; pending = null }
      }
    } else if (!reduce && rise < 0.25) {
      hold += dt
      if (hold >= HOLD) advance()
    }
  })
}

function lift() {
  document.documentElement.classList.add('ready')
  veil.classList.add('gone')
  setTimeout(() => veil.remove(), 1300)
}

start()

// --- Everything below the hero ----------------------------------------------------------------------

const $ = (s, root = document) => root.querySelector(s)
const $$ = (s, root = document) => [...root.querySelectorAll(s)]

const revealer = new IntersectionObserver((entries) => {
  for (const e of entries) if (e.isIntersecting) { e.target.classList.add('in'); revealer.unobserve(e.target) }
}, { threshold: 0.18, rootMargin: '0px 0px -6% 0px' })
$$('[data-reveal]').forEach((el) => revealer.observe(el))

// Only what's on the screen does any work.
const onScreen = new WeakMap()
const watcher = new IntersectionObserver((entries) => { for (const e of entries) onScreen.set(e.target, e.isIntersecting) }, { rootMargin: '20% 0px' })
$$('[data-watch]').forEach((el) => watcher.observe(el))
const through = (el) => { const r = el.getBoundingClientRect(); return clamp(-r.top / Math.max(1, r.height - innerHeight)) }

// The creed, a word at a time.
{
  const lit = $('.lit')
  const section = $('.creed')
  lit.innerHTML = lit.textContent.split(' ').map((w) => `<span>${w}</span>`).join(' ')
  const words = $$('span', lit)
  const follow = new Spring(0, 0.42, 1)
  let done = -1
  tickers.add((dt) => {
    if (!onScreen.get(section)) return
    follow.target = reduce ? 1 : ramp(through(section), 0.02, 0.86)
    follow.step(dt)
    const at = follow.x * words.length
    const key = Math.round(at * 60)
    if (key === done) return
    done = key
    words.forEach((w, i) => { w.style.opacity = (0.13 + 0.87 * clamp(at - i)).toFixed(3) })
  })
}

// The room: each screen comes up from below, overexposed, and develops. The app's own arrival.
{
  const section = $('#room')
  const shots = $$('.phone__screen img')
  const plates = $$('.plate')
  const rows = $$('.room__index li')
  const glow = $('#room-glow')
  glow.innerHTML = shots.map((s, i) => `<img src="${s.getAttribute('src')}" alt="" loading="lazy"${i ? '' : ' class="on"'}>`).join('')
  const glows = $$('img', glow)
  const follow = new Spring(0, 0.4, 1)
  let active = 0
  shots.forEach((s, i) => { if (i) { s.style.clipPath = 'inset(100% 0 0 0)' } })
  tickers.add((dt) => {
    if (!onScreen.get(section)) return
    follow.target = ramp(through(section), 0.04, 0.96) * (shots.length - 1)
    follow.step(dt)
    shots.forEach((s, i) => {
      if (!i) return
      const local = reduce ? (follow.x >= i - 0.5 ? 1 : 0) : clamp((follow.x - (i - 1)) * 1.5 - 0.25)
      const e = local * local * (3 - 2 * local)
      s.style.clipPath = `inset(${((1 - e) * 100).toFixed(2)}% 0 0 0)`
      s.style.filter = e < 1 ? `brightness(${(1 + (1 - e) * 1.7).toFixed(3)})` : 'none'
    })
    const now = Math.round(follow.x)
    if (now !== active) {
      active = now
      plates.forEach((p, i) => p.classList.toggle('on', i === active))
      rows.forEach((p, i) => p.classList.toggle('on', i === active))
      glows.forEach((p, i) => p.classList.toggle('on', i === active))
    }
  })
}

// The equaliser's bench: the same sums the app runs, on the same measurements.
{
  const section = $('#sound')
  const canvas = $('#curve')
  const g = canvas.getContext('2d')
  const list = $('#models')
  const nameEl = $('#curve-name')
  const byEl = $('#curve-by')
  const WANT = [['Apple', 'AirPods Max'], ['Apple', 'AirPods Pro 2'], ['Sennheiser', 'HD 800 S'], ['Sennheiser', 'HD 600'], ['Sony', 'WH-1000XM5'], ['Marshall', 'Major III'], ['Bose', 'QuietComfort 45'], ['Focal', 'Utopia']]
  const POINTS = 180
  const RANGE = 12
  const at = new Float32Array(POINTS)
  const speed = new Float32Array(POINTS)
  let target = new Float32Array(POINTS)
  let chosen = 0
  let touched = false
  let wait = 0
  let moving = false
  let models = []
  const K = (2 * Math.PI / 0.55) ** 2
  const C = 2 * 0.82 * (2 * Math.PI / 0.55)

  function coefficients([type, hz, gain, q], fs) {
    const w = (2 * Math.PI * hz) / fs, c = Math.cos(w), alpha = Math.sin(w) / (2 * q), a = 10 ** (gain / 40), s = 2 * Math.sqrt(a) * alpha
    const r = type === 'PK' ? [1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a]
      : type === 'LSC' ? [a * (a + 1 - (a - 1) * c + s), 2 * a * (a - 1 - (a + 1) * c), a * (a + 1 - (a - 1) * c - s), a + 1 + (a - 1) * c + s, -2 * (a - 1 + (a + 1) * c), a + 1 + (a - 1) * c - s]
        : [a * (a + 1 + (a - 1) * c + s), -2 * a * (a - 1 + (a + 1) * c), a * (a + 1 + (a - 1) * c - s), a + 1 - (a - 1) * c + s, 2 * (a - 1 - (a + 1) * c), a + 1 - (a - 1) * c - s]
    return [r[0] / r[3], r[1] / r[3], r[2] / r[3], r[4] / r[3], r[5] / r[3]]
  }
  function db(c, hz, fs) {
    const w = (2 * Math.PI * hz) / fs, cw = Math.cos(w), sw = Math.sin(w), c2 = Math.cos(2 * w), s2 = Math.sin(2 * w)
    const nr = c[0] + c[1] * cw + c[2] * c2, ni = c[1] * sw + c[2] * s2, dr = 1 + c[3] * cw + c[4] * c2, di = c[3] * sw + c[4] * s2
    return 10 * Math.log10((nr * nr + ni * ni) / (dr * dr + di * di))
  }
  function curveOf(filters) {
    const cs = filters.map((f) => coefficients(f, 48000))
    return Float32Array.from({ length: POINTS }, (_, i) => { const hz = 20 * 1000 ** (i / (POINTS - 1)); return cs.reduce((sum, c) => sum + db(c, hz, 48000), 0) })
  }
  function draw() {
    const r = canvas.getBoundingClientRect()
    const dpr = Math.min(devicePixelRatio || 1, 2)
    if (canvas.width !== Math.round(r.width * dpr)) { canvas.width = Math.round(r.width * dpr); canvas.height = Math.round(r.height * dpr) }
    const w = canvas.width, h = canvas.height
    const foot = 26 * dpr, ch = h - foot, mid = ch / 2, per = mid / RANGE
    g.clearRect(0, 0, w, h)
    g.lineWidth = dpr * 0.75
    const hline = (y, a) => { g.strokeStyle = `rgba(255,255,255,${a})`; g.beginPath(); g.moveTo(0, y); g.lineTo(w, y); g.stroke() }
    hline(mid, 0.34)
    for (const d of [-12, -6, 6, 12]) hline(mid - d * per + (Math.abs(d) === 12 ? (d > 0 ? 1 : -1) * dpr : 0), 0.09)
    g.font = `500 ${9 * dpr}px Montserrat`
    g.textAlign = 'center'
    for (const [hz, label] of [[50, '50'], [100, '100'], [500, '500'], [1000, '1K'], [5000, '5K'], [10000, '10K']]) {
      const x = (Math.log(hz / 20) / Math.log(1000)) * w
      g.strokeStyle = 'rgba(255,255,255,.09)'
      g.beginPath(); g.moveTo(x, 0); g.lineTo(x, ch); g.stroke()
      g.fillStyle = '#5e5e5e'
      g.fillText(label, x, h - 6 * dpr)
    }
    const path = new Path2D()
    for (let i = 0; i < POINTS; i++) {
      const x = (i / (POINTS - 1)) * w, y = mid - clamp(at[i], -RANGE, RANGE) * per
      if (i) path.lineTo(x, y); else path.moveTo(x, y)
    }
    const fill = new Path2D(path)
    fill.lineTo(w, mid); fill.lineTo(0, mid); fill.closePath()
    const shade = g.createLinearGradient(0, 0, 0, ch)
    shade.addColorStop(0, 'rgba(255,255,255,.2)'); shade.addColorStop(0.5, 'rgba(255,255,255,.02)'); shade.addColorStop(1, 'rgba(255,255,255,.2)')
    g.fillStyle = shade
    g.fill(fill)
    g.strokeStyle = '#fff'
    g.lineWidth = dpr * 1.6
    g.lineJoin = 'round'
    g.stroke(path)
  }
  function choose(i, byHand) {
    if (byHand) touched = true
    chosen = i
    wait = 0
    moving = true
    const m = models[i]
    target = curveOf(m.f)
    nameEl.textContent = `${m.b} ${m.n}`
    byEl.textContent = `Measured by ${m.by}`
    $$('button', list).forEach((b, k) => b.setAttribute('aria-pressed', String(k === i)))
  }
  fetch('assets/media/headphones.json').then((r) => r.json()).then((all) => {
    models = WANT.map(([b, n]) => all.find((m) => m.b === b && m.n === n)).filter(Boolean)
    list.innerHTML = models.map((m) => `<li><button aria-pressed="false">${m.b === 'Apple' ? '' : m.b + ' '}${m.n}</button></li>`).join('')
    $$('button', list).forEach((b, i) => {
      b.addEventListener('click', () => choose(i, true))
      if (!coarse) b.addEventListener('pointerenter', () => choose(i, true))
    })
    document.fonts.ready.then(() => { choose(0); draw() })
  })
  tickers.add((dt) => {
    if (!onScreen.get(section) || !models.length) return
    if (!touched && !reduce) { wait += dt; if (wait > 3.4) choose((chosen + 1) % models.length) }
    if (!moving) return
    if (reduce) { at.set(target); speed.fill(0); moving = false; draw(); return }
    let left = Math.min(dt, 1 / 30), still = true
    while (left > 0) {
      const h = Math.min(left, 1 / 240)
      for (let i = 0; i < POINTS; i++) { speed[i] += (-K * (at[i] - target[i]) - C * speed[i]) * h; at[i] += speed[i] * h }
      left -= h
    }
    for (let i = 0; i < POINTS; i++) if (Math.abs(at[i] - target[i]) > 0.01 || Math.abs(speed[i]) > 0.05) { still = false; break }
    if (still) { at.set(target); speed.fill(0); moving = false }
    draw()
  })
  addEventListener('resize', () => { if (models.length) draw() })
}

// The hand: the app's dial, drawn large. Scrolling moves it; so does taking hold of it.
{
  const section = $('#hand')
  const canvas = $('#dial')
  const g = canvas.getContext('2d')
  const nowEl = $('#dial-now')
  const leftEl = $('#dial-left')
  const LENGTH = 236
  const SWEEP = (Math.PI * 200) / 180
  const value = new Spring(0.2, 0.34, 0.95)
  let held = false, pivot = { x: 0, y: 0 }, radius = 0, dirty = true, lastKey = ''
  const stamp = (s) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`

  function size() {
    const r = canvas.getBoundingClientRect()
    const dpr = Math.min(devicePixelRatio || 1, 2)
    canvas.width = Math.round(r.width * dpr)
    canvas.height = Math.round(r.height * dpr)
    radius = Math.min(r.width * (r.width < 700 ? 0.46 : 0.42), r.height * 0.5)
    pivot = { x: r.width / 2, y: r.height - Math.max(12, r.height * 0.02) }
    dirty = true
  }
  const toAngle = (t) => -Math.PI / 2 - SWEEP / 2 + SWEEP * t
  function draw() {
    const dpr = Math.min(devicePixelRatio || 1, 2)
    g.setTransform(dpr, 0, 0, dpr, 0, 0)
    g.clearRect(0, 0, canvas.width, canvas.height)
    const t = value.x
    const TICKS = 120
    for (let i = 0; i <= TICKS; i++) {
      const p = i / TICKS, a = toAngle(p), major = i % 10 === 0, passed = p <= t
      const rIn = radius - (major ? 26 : 14), rOut = radius
      g.strokeStyle = `rgba(255,255,255,${passed ? (major ? 0.95 : 0.6) : (major ? 0.4 : 0.18)})`
      g.lineWidth = major ? 1.6 : 1
      g.beginPath()
      g.moveTo(pivot.x + Math.cos(a) * rIn, pivot.y + Math.sin(a) * rIn)
      g.lineTo(pivot.x + Math.cos(a) * rOut, pivot.y + Math.sin(a) * rOut)
      g.stroke()
    }
    g.strokeStyle = 'rgba(255,255,255,.1)'
    g.lineWidth = 1
    for (const extra of [34]) { g.beginPath(); g.arc(pivot.x, pivot.y, radius + extra, toAngle(0) - 0.02, toAngle(1) + 0.02); g.stroke() }
    const a = toAngle(t)
    const foot = { x: pivot.x + Math.cos(a) * radius * 0.56, y: pivot.y + Math.sin(a) * radius * 0.56 }
    g.strokeStyle = '#ff3b30'
    g.lineWidth = held ? 2.8 : 2.1
    g.lineCap = 'round'
    g.beginPath()
    g.moveTo(foot.x, foot.y)
    g.lineTo(pivot.x + Math.cos(a) * (radius + 9), pivot.y + Math.sin(a) * (radius + 9))
    g.stroke()
    g.fillStyle = '#ff3b30'
    g.beginPath(); g.arc(foot.x, foot.y, 3.8, 0, 7); g.fill()
    const secs = t * LENGTH
    const key = stamp(secs)
    if (key !== lastKey) { lastKey = key; nowEl.textContent = key; leftEl.textContent = `−${stamp(LENGTH - secs)}` }
  }
  function grab(e) {
    const r = canvas.getBoundingClientRect()
    const x = e.clientX - r.left - pivot.x, y = e.clientY - r.top - pivot.y
    let a = Math.atan2(y, x) + Math.PI / 2
    if (a > Math.PI) a -= 2 * Math.PI
    value.target = clamp((a + SWEEP / 2) / SWEEP)
  }
  canvas.addEventListener('pointerdown', (e) => {
    const r = canvas.getBoundingClientRect()
    const d = Math.hypot(e.clientX - r.left - pivot.x, e.clientY - r.top - pivot.y)
    if (d < radius * 0.42 || d > radius + 70) return
    held = true
    canvas.classList.add('held')
    canvas.setPointerCapture(e.pointerId)
    grab(e)
  })
  canvas.addEventListener('pointermove', (e) => { if (held) grab(e) })
  const drop = () => { held = false; canvas.classList.remove('held') }
  canvas.addEventListener('pointerup', drop)
  canvas.addEventListener('pointercancel', drop)
  new ResizeObserver(size).observe(canvas)
  let touched = false
  canvas.addEventListener('pointerdown', () => { touched = true })
  tickers.add((dt) => {
    if (!onScreen.get(section)) return
    if (!held && !touched && !reduce) {
      const r = section.getBoundingClientRect()
      value.target = 0.12 + 0.7 * clamp(1 - (r.top + r.height * 0.4) / innerHeight)
    }
    const was = value.x
    value.step(dt)
    if (dirty || Math.abs(was - value.x) > 0.00001) { dirty = false; draw() }
  })
}

// The metal: the app's AGSL, in GLSL. Black or silver, with the light where the pointer is.
{
  const section = $('#metal')
  const card = $('#card')
  const canvas = $('#card-metal')
  const milled = $('#milled')
  milled.innerHTML = Array.from({ length: 96 }, (_, k) => {
    const a = (k * 3.75 * Math.PI) / 180
    return `<line x1="${(78 + Math.cos(a) * 71.5).toFixed(2)}" y1="${(78 + Math.sin(a) * 71.5).toFixed(2)}" x2="${(78 + Math.cos(a) * 75.5).toFixed(2)}" y2="${(78 + Math.sin(a) * 75.5).toFixed(2)}"/>`
  }).join('')
  const gl = canvas.getContext('webgl', { antialias: false })
  let finish = 0, finishTarget = 0, dirty = true, uni = null
  const rx = new Spring(0, 0.5, 0.82), ry = new Spring(0, 0.5, 0.82), lx = new Spring(0.3, 0.5, 0.9), ly = new Spring(0.26, 0.5, 0.9)
  if (gl) {
    const vs = gl.createShader(gl.VERTEX_SHADER)
    gl.shaderSource(vs, 'attribute vec2 p;void main(){gl_Position=vec4(p,0.,1.);}')
    gl.compileShader(vs)
    const fs = gl.createShader(gl.FRAGMENT_SHADER)
    gl.shaderSource(fs, `precision highp float;
uniform vec2 size, light; uniform float silver;
float hash(vec2 p){vec3 q=fract(vec3(p.xyx)*.1031);q+=dot(q,q.yzx+33.33);return fract((q.x+q.y)*q.z);}
float noise(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(hash(i),hash(i+vec2(1.,0.)),f.x),mix(hash(i+vec2(0.,1.)),hash(i+vec2(1.,1.)),f.x),f.y);}
void main(){vec2 p=vec2(gl_FragCoord.x,size.y-gl_FragCoord.y);vec2 uv=p/size;
float grain=noise(vec2(p.x*.006,p.y*1.4))*.6+noise(vec2(p.x*.02,p.y*3.1))*.4;
float pitch=size.x/170.;float turned=.5+.5*sin(length(p-vec2(-.35*size.x,.18*size.y))/pitch*6.28318);
float d=dot(uv-light,vec2(.42,.91));float sheen=exp(-d*d*7.)+exp(-(d-.72)*(d-.72)*14.)*.35;
float b=.046+grain*.016+turned*(.006+sheen*.036)+sheen*.075;float s=.6+grain*.07+turned*.024+sheen*.27;
float v=mix(b,s,silver);gl_FragColor=vec4(v*.985,v*.992,min(v*1.012,1.),1.);}`)
    gl.compileShader(fs)
    const p = gl.createProgram()
    gl.attachShader(p, vs); gl.attachShader(p, fs); gl.linkProgram(p); gl.useProgram(p)
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer())
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW)
    gl.enableVertexAttribArray(0)
    gl.bindAttribLocation(p, 0, 'p')
    gl.linkProgram(p)
    gl.useProgram(p)
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0)
    uni = { size: gl.getUniformLocation(p, 'size'), light: gl.getUniformLocation(p, 'light'), silver: gl.getUniformLocation(p, 'silver') }
    const fit = () => {
      const r = canvas.getBoundingClientRect(), dpr = Math.min(devicePixelRatio || 1, 2)
      canvas.width = Math.round(card.offsetWidth * dpr); canvas.height = Math.round(card.offsetHeight * dpr)
      gl.viewport(0, 0, canvas.width, canvas.height)
      dirty = true
      return r
    }
    new ResizeObserver(fit).observe(card)
  } else { canvas.style.background = 'linear-gradient(140deg,#17181a,#0a0a0b,#131416)' }

  function pick(which) {
    section.dataset.finish = which
    finishTarget = which === 'silver' ? 1 : 0
    $$('[data-pick]').forEach((b) => b.setAttribute('aria-checked', String(b.dataset.pick === which)))
  }
  $$('[data-pick]').forEach((b) => b.addEventListener('click', () => pick(b.dataset.pick)))

  if (!coarse) {
    section.addEventListener('pointermove', (e) => {
      const r = card.getBoundingClientRect()
      const x = clamp((e.clientX - r.left) / r.width, -0.6, 1.6), y = clamp((e.clientY - r.top) / r.height, -0.6, 1.6)
      ry.target = (x - 0.5) * 16
      rx.target = (0.5 - y) * 12
      lx.target = x
      ly.target = y
    })
    section.addEventListener('pointerleave', () => { rx.target = 0; ry.target = 0; lx.target = 0.3; ly.target = 0.26 })
  }
  tickers.add((dt, time) => {
    if (!onScreen.get(section)) return
    if (coarse && !reduce) { ry.target = Math.sin(time * 0.6) * 8; rx.target = Math.cos(time * 0.45) * 5; lx.target = 0.5 + Math.sin(time * 0.6) * 0.5; ly.target = 0.4 + Math.cos(time * 0.45) * 0.4 }
    for (const s of [rx, ry, lx, ly]) s.step(dt)
    if (!reduce) card.style.transform = `rotateX(${rx.x.toFixed(3)}deg) rotateY(${ry.x.toFixed(3)}deg)`
    const f = finish + (finishTarget - finish) * Math.min(1, dt * 7)
    if (gl && (dirty || Math.abs(f - finish) > 0.0005 || !lx.settled || !ly.settled)) {
      finish = f
      dirty = false
      gl.uniform2f(uni.size, canvas.width, canvas.height)
      gl.uniform2f(uni.light, lx.x, ly.x)
      gl.uniform1f(uni.silver, finish)
      gl.drawArrays(gl.TRIANGLES, 0, 3)
    }
  })
}

// The omissions: raking light across the letters, as they pass through the window.
{
  const section = $('#sine')
  const lines = $$('.latin')
  tickers.add(() => {
    if (!onScreen.get(section)) return
    for (const l of lines) {
      const r = l.getBoundingClientRect()
      const v = reduce ? 1 : clamp(1 - (r.top + r.height / 2 - innerHeight * 0.34) / (innerHeight * 0.44))
      l.style.setProperty('--lit', v.toFixed(3))
    }
  })
}

// The sea plays only while it can be seen.
{
  const sea = $('#sea')
  new IntersectionObserver(([e]) => {
    if (e.isIntersecting) { if (!reduce) sea.play().catch(() => {}) } else sea.pause()
  }, { rootMargin: '30% 0px' }).observe(sea)
}

// The dock: the tab bar's glass, its lit drop on a spring.
{
  const dock = $('#dock')
  const metalSection = $('#metal')
  const links = $$('a', dock)
  const drop = $('.dock__drop')
  const chapters = $$('[data-chapter]')
  const x = new Spring(0, 0.42, 0.78)
  const bar = $('.bar')
  const travel = $('#travel')
  let active = -1

  // A number doesn't jump to its chapter, it glides there on a spring. A long way goes past behind
  // darkened, frosted glass, so what you see is the leaving and the arriving.
  const glide = new Spring(0, 1, 1)
  let going = false, from = 0, far = 0, heading = null
  const land = () => {
    if (!going) return
    going = false
    heading = null
    travel.style.opacity = 0
    travel.style.visibility = 'hidden'
  }
  for (const event of ['wheel', 'touchstart', 'keydown']) addEventListener(event, land, { passive: true })
  $$('a[href^="#"]').forEach((a) => a.addEventListener('click', (e) => {
    const place = $(a.getAttribute('href'))
    if (!place) return
    e.preventDefault()
    const target = Math.round(place.getBoundingClientRect().top + scrollY)
    const at = links.indexOf(a)
    if (reduce) { scrollTo({ top: target, behavior: 'instant' }); return }
    const distance = Math.abs(target - scrollY)
    from = scrollY
    far = ramp(distance, innerHeight * 1.1, innerHeight * 3.4)
    glide.x = scrollY
    glide.v = 0
    glide.target = target
    glide.omega = (2 * Math.PI) / (0.8 + 0.75 * clamp(distance / 9000))
    heading = at >= 0 ? at : null
    going = true
    if (far > 0) travel.style.visibility = 'visible'
  }))
  tickers.add((dt) => {
    if (going) {
      glide.step(dt)
      scrollTo({ top: glide.x, behavior: 'instant' })
      const moved = clamp((glide.x - from) / ((glide.target - from) || 1))
      travel.style.opacity = (far * Math.min(1, Math.sin(Math.PI * moved) * 1.7)).toFixed(3)
      if (Math.abs(glide.x - glide.target) < 0.6) { scrollTo({ top: glide.target, behavior: 'instant' }); land() }
    }
    const page = document.documentElement
    bar.classList.toggle('over', scrollY > 24)
    dock.classList.toggle('up', scrollY > hero.offsetHeight * 0.62 && scrollY + innerHeight < page.scrollHeight - 200)
    const m = metalSection.getBoundingClientRect()
    const lightStage = metalSection.dataset.finish === 'black'
    page.classList.toggle('light-top', lightStage && m.top < 44 && m.bottom > 44)
    page.classList.toggle('light-foot', lightStage && m.top < innerHeight - 40 && m.bottom > innerHeight - 40)
    let now = 0
    chapters.forEach((c, i) => { if (c.getBoundingClientRect().top < innerHeight * 0.5) now = i })
    if (heading != null) now = heading
    if (now !== active) {
      active = now
      links.forEach((l, i) => { l.classList.toggle('on', i === now); if (i === now) l.setAttribute('aria-current', 'true'); else l.removeAttribute('aria-current') })
      x.target = links[now].offsetLeft - 5
    }
    x.step(dt)
    drop.style.transform = `translate3d(${x.x.toFixed(2)}px, 0, 0)`
  })

  // The refraction at the glass's rim, where the browser can do it.
  if (window.chrome && !coarse) {
    const make = () => {
      const W = dock.offsetWidth, H = dock.offsetHeight
      if (!W) return
      const c = document.createElement('canvas')
      c.width = W; c.height = H
      const g = c.getContext('2d')
      const img = g.createImageData(W, H)
      const rad = H / 2, bezel = 13
      for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
        const qx = Math.abs(x + 0.5 - W / 2) - (W / 2 - rad), qy = Math.abs(y + 0.5 - H / 2) - (H / 2 - rad)
        const out = Math.hypot(Math.max(qx, 0), Math.max(qy, 0))
        const dist = rad - out - (qx < 0 && qy < 0 ? -Math.max(qx, qy) : 0)
        const k = clamp(1 - dist / bezel) ** 2
        const len = out || 1
        const nx = out ? (Math.max(qx, 0) / len) * Math.sign(x + 0.5 - W / 2) : 0
        const ny = out ? (Math.max(qy, 0) / len) * Math.sign(y + 0.5 - H / 2) : Math.sign(y + 0.5 - H / 2)
        const i = (y * W + x) * 4
        img.data[i] = 128 - nx * k * 127
        img.data[i + 1] = 128 - ny * k * 127
        img.data[i + 2] = 128
        img.data[i + 3] = 255
      }
      g.putImageData(img, 0, 0)
      const map = $('#lens-map'), filter = $('#lens')
      map.setAttribute('href', c.toDataURL())
      for (const el of [map, filter]) { el.setAttribute('x', 0); el.setAttribute('y', 0); el.setAttribute('width', W); el.setAttribute('height', H) }
      filter.setAttribute('filterUnits', 'userSpaceOnUse')
      filter.setAttribute('primitiveUnits', 'userSpaceOnUse')
      dock.classList.add('lens-on')
      document.documentElement.classList.add('lens')
    }
    document.fonts.ready.then(make)
  }
}
