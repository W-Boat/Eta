import { spawnSync } from 'node:child_process'
import { copyFile, mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const project = fileURLToPath(new URL('.', import.meta.url))
const output = resolve(process.argv[2] || 'dist', 'muya')

if (process.argv.includes('--install')) {
    const windows = process.platform === 'win32'
    const install = spawnSync(windows ? 'npm.cmd' : 'npm', ['ci', '--ignore-scripts', '--no-audit', '--no-fund'], {
        cwd: project,
        shell: windows,
        stdio: 'inherit',
    })
    if (install.error) throw install.error
    if (install.status !== 0) process.exit(install.status ?? 1)
}

const { build } = await import('esbuild')
await mkdir(output, { recursive: true })
await build({
    absWorkingDir: project,
    entryPoints: { editor: 'editor.js' },
    outdir: output,
    bundle: true,
    splitting: true,
    minify: true,
    format: 'esm',
    target: 'chrome110',
    assetNames: 'assets/[name]-[hash]',
    chunkNames: 'chunks/[name]-[hash]',
    loader: {
        '.png': 'file',
        '.jpg': 'file',
        '.svg': 'file',
        '.woff': 'file',
        '.woff2': 'file',
        '.ttf': 'file',
        '.eot': 'file',
    },
    legalComments: 'external',
    logLevel: 'warning',
})
await copyFile(resolve(project, 'index.html'), resolve(output, 'index.html'))
await copyFile(resolve(project, 'node_modules/@muyajs/core/LICENSE'), resolve(output, 'MUYA-LICENSE.txt'))
