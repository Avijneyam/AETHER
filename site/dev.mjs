// The site's local server: serves this folder and reloads the page when a file changes.
// node site/dev.mjs   then http://localhost:4173. Not part of what's deployed.
import { createServer } from 'node:http'
import { watch } from 'node:fs'
import { readdir, readFile, stat } from 'node:fs/promises'
import { extname, join, normalize, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(fileURLToPath(new URL('.', import.meta.url)))
const port = Number(process.env.PORT || 4173)
const types = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.json': 'application/json', '.jpg': 'image/jpeg', '.png': 'image/png',
  '.webp': 'image/webp', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.woff': 'font/woff', '.mp4': 'video/mp4',
}
const listeners = new Set()
const reloader = `<script>new EventSource('/__live').onmessage=e=>{
  if(e.data==='css'){document.querySelectorAll('link[rel=stylesheet]').forEach(l=>{l.href=l.href.split('?')[0]+'?'+Date.now()})}
  else location.reload()}</script>`

createServer(async (req, res) => {
  const path = decodeURIComponent(new URL(req.url, 'http://x').pathname)
  if (path === '/__live') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', Connection: 'keep-alive' })
    res.write('retry: 400\n\n')
    listeners.add(res)
    req.on('close', () => listeners.delete(res))
    return
  }
  const file = normalize(join(root, path === '/' ? 'index.html' : path))
  if (!file.startsWith(root + sep)) { res.writeHead(403).end(); return }
  try {
    if (!(await stat(file)).isFile()) throw 0
    let body = await readFile(file)
    const type = types[extname(file)] || 'application/octet-stream'
    if (type.startsWith('text/html')) body = String(body).replace('</body>', reloader + '</body>')
    res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store' }).end(body)
  } catch { res.writeHead(404).end('Not found') }
}).listen(port, '127.0.0.1', () => console.log(`http://localhost:${port}`))

let timer
let cssOnly = true
// Only a new modification time counts as a change, so that nothing but a real edit reloads the page.
const known = new Map()
for (const name of await readdir(root, { recursive: true })) {
  try { known.set(name, (await stat(join(root, name))).mtimeMs) } catch {}
}
watch(root, { recursive: true }, async (_, name) => {
  if (!name) return
  let time
  try {
    const info = await stat(join(root, name))
    if (!info.isFile()) return
    time = info.mtimeMs
  } catch { return }
  if (known.get(name) === time) return
  known.set(name, time)
  cssOnly &&= name.endsWith('.css')
  clearTimeout(timer)
  timer = setTimeout(() => {
    for (const l of listeners) l.write(`data: ${cssOnly ? 'css' : 'reload'}\n\n`)
    cssOnly = true
  }, 90)
})
