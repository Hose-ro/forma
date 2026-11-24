import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { accessSync, constants, copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import test from 'node:test';

test('the opt-in installer activates only reviewed hooks in the shared default directory', (t) => {
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'olvenio-hooks-')));
  t.after(() => rmSync(temp, { recursive: true, force: true }));

  const root = join(temp, 'main checkout');
  const linked = join(temp, 'linked checkout');
  const bin = join(temp, 'bin');
  const marker = join(temp, 'gitleaks.log');
  const checkoutMarker = join(temp, 'checkout-marker');
  const env = {
    ...Object.fromEntries(Object.entries(process.env).filter(([name]) => !name.startsWith('GIT_'))),
    GIT_CONFIG_NOSYSTEM: '1',
    GIT_CONFIG_GLOBAL: '/dev/null',
    PATH: `${bin}${delimiter}${process.env.PATH}`,
    GITLEAKS_MARKER: marker,
    OLVENIO_CHECKOUT_MARKER: checkoutMarker,
  };
  const git = (...args) => execFileSync('git', ['-C', root, ...args], { env, encoding: 'utf8' }).trim();
  const install = (cwd) => spawnSync('npm', ['run', 'hooks:install'], { cwd, env, encoding: 'utf8' });

  mkdirSync(root);
  mkdirSync(bin);
  git('init', '--quiet', '--template=');
  git('-c', 'user.name=Local test', '-c', 'user.email=test@example.test', 'commit', '--quiet', '--allow-empty', '-m', 'Fixture');
  git('worktree', 'add', '--quiet', '--detach', linked);

  const reviewedHooks = ['_gitleaks.sh', 'pre-commit', 'pre-push'];
  mkdirSync(join(linked, '.githooks'));
  for (const file of ['package.json', ...[...reviewedHooks, 'install.sh'].map((hook) => `.githooks/${hook}`)]) {
    copyFileSync(new URL(`../${file}`, import.meta.url), join(linked, file));
  }
  writeFileSync(join(linked, '.githooks', 'unreviewed'), 'fixture');
  for (const worktree of [root, linked]) writeFileSync(join(worktree, '.gitleaks.toml'), 'fixture');
  writeFileSync(join(bin, 'gitleaks'), `#!/bin/sh
config=
mode=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --config) shift; config=$1 ;;
    --staged) mode=staged ;;
    --log-opts=*) mode=history ;;
  esac
  root=$1
  shift
done
[ "$config" = "$root/.gitleaks.toml" ] && [ -f "$config" ] || exit 1
printf '%s|%s\n' "$mode" "$config" >> "$GITLEAKS_MARKER"
`, { mode: 0o755 });

  const result = install(linked);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(spawnSync('git', ['-C', root, 'config', '--get', 'core.hooksPath'], { env }).status, 1);
  const hooks = git('rev-parse', '--path-format=absolute', '--git-path', 'hooks');
  assert.deepEqual(readdirSync(hooks).sort(), reviewedHooks);
  accessSync(join(hooks, 'pre-commit'), constants.X_OK);
  accessSync(join(hooks, 'pre-push'), constants.X_OK);

  git('hook', 'run', 'pre-commit');
  const refs = join(temp, 'refs');
  writeFileSync(refs, `HEAD ${git('rev-parse', 'HEAD')} refs/heads/fixture ${'0'.repeat(40)}\n`);
  execFileSync('git', ['-C', linked, 'hook', 'run', `--to-stdin=${refs}`, 'pre-push'], { env });
  assert.equal(readFileSync(marker, 'utf8'), `staged|${root}/.gitleaks.toml\nhistory|${linked}/.gitleaks.toml\n`);

  git('config', 'core.hooksPath', '.githooks');
  assert.equal(install(linked).status, 0);
  assert.equal(spawnSync('git', ['-C', root, 'config', '--get', 'core.hooksPath'], { env }).status, 1);

  git('switch', '--quiet', '-c', 'unreviewed-checkout');
  mkdirSync(join(root, '.githooks'));
  writeFileSync(join(root, '.githooks', 'post-checkout'), '#!/bin/sh\n: > "$OLVENIO_CHECKOUT_MARKER"\n', { mode: 0o755 });
  git('add', '.githooks/post-checkout');
  git('-c', 'user.name=Local test', '-c', 'user.email=test@example.test', 'commit', '--quiet', '-m', 'Checkout marker fixture');
  git('switch', '--quiet', '--detach', 'HEAD^');
  git('switch', '--quiet', 'unreviewed-checkout');
  assert.equal(existsSync(checkoutMarker), false);

  git('config', 'core.hooksPath', 'custom-hooks');
  assert.equal(install(linked).status, 1);
  assert.equal(git('config', '--get', 'core.hooksPath'), 'custom-hooks');
  git('config', '--unset', 'core.hooksPath');

  const included = join(temp, 'included.gitconfig');
  writeFileSync(included, '[core]\n  hooksPath = .githooks\n');
  git('config', 'include.path', included);
  assert.equal(install(linked).status, 1);
  assert.equal(git('config', '--get', 'core.hooksPath'), '.githooks');
  git('config', '--unset', 'include.path');

  git('config', 'extensions.worktreeConfig', 'true');
  execFileSync('git', ['-C', linked, 'config', '--worktree', 'core.hooksPath', '.githooks'], { env });
  assert.equal(install(linked).status, 1);
  execFileSync('git', ['-C', linked, 'config', '--worktree', '--unset', 'core.hooksPath'], { env });

  const existing = join(hooks, 'pre-commit');
  writeFileSync(existing, '#!/bin/sh\n# Local hook\n');
  assert.equal(install(linked).status, 1);
  assert.equal(readFileSync(existing, 'utf8'), '#!/bin/sh\n# Local hook\n');
});
