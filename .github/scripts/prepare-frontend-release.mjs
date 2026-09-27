import { execFileSync } from 'node:child_process';
import { appendFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const semver = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;

function compareVersions(left, right) {
  const a = left.split('.').map(BigInt);
  const b = right.split('.').map(BigInt);
  for (let index = 0; index < 3; index += 1) {
    if (a[index] !== b[index]) return a[index] > b[index] ? 1 : -1;
  }
  return 0;
}

export function prepareRelease(commit, deploymentUrl, cwd = process.cwd()) {
  if (!/^[0-9a-f]{40}$/.test(commit ?? '')) {
    throw new Error('운영 배포에 사용한 40자리 커밋 SHA를 입력하세요.');
  }
  const url = new URL(deploymentUrl);
  if (url.protocol !== 'https:' || url.username || url.password) {
    throw new Error('배포 기록은 인증 정보가 없는 HTTPS URL이어야 합니다.');
  }
  const git = (...args) =>
    execFileSync('git', args, {
      cwd,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    }).trim();

  // 현재 HEAD가 아니라 운영 배포에 사용한 커밋의 파일을 읽는다.
  git('merge-base', '--is-ancestor', commit, 'origin/main');
  const { version } = JSON.parse(
    git('show', `${commit}:frontend/web/package.json`),
  );
  if (typeof version !== 'string' || !semver.test(version)) {
    throw new Error(
      '프론트 버전은 정식 릴리스용 MAJOR.MINOR.PATCH 형식이어야 합니다.',
    );
  }
  const tag = `frontend-v${version}`;
  const tags = git('tag', '--list', 'frontend-v*').split('\n').filter(Boolean);
  for (const existing of tags) {
    if (existing === tag) {
      if (git('rev-parse', `${tag}^{commit}`) !== commit) {
        throw new Error(
          `${tag}가 다른 커밋에 있습니다. 기존 태그를 옮기지 말고 버전을 올리세요.`,
        );
      }
      continue;
    }
    const previous = existing.slice('frontend-v'.length);
    if (semver.test(previous) && compareVersions(version, previous) <= 0) {
      throw new Error(
        `${version}은 기존 릴리스 ${previous}보다 높아야 합니다.`,
      );
    }
  }

  const changelog = git('show', `${commit}:frontend/web/CHANGELOG.md`);
  const sections = [...changelog.matchAll(/^## (.+)\r?$/gm)];
  const matches = sections.filter((section) => section[1].trim() === version);
  if (matches.length !== 1) {
    throw new Error(
      `CHANGELOG.md에 '## ${version}' 항목이 정확히 하나 필요합니다.`,
    );
  }
  const section = matches[0];
  const next = sections.find((candidate) => candidate.index > section.index);
  const changes = changelog
    .slice(section.index + section[0].length, next?.index)
    .trim();
  if (!/^- \S/m.test(changes)) {
    throw new Error('해당 버전의 변경 내역을 불릿으로 작성하세요.');
  }

  return {
    version,
    tag,
    commit,
    tagExists: tags.includes(tag),
    notes: [
      `프론트엔드 버전: ${version}`,
      `배포 커밋: ${commit}`,
      '배포 환경: production',
      `배포 기록: ${url.href}`,
      '',
      '운영 배포 성공과 주요 동작을 실행자가 확인한 뒤 작성한 릴리스 기록입니다.',
      '이 워크플로는 AWS 배포 상태를 직접 조회하지 않습니다.',
      '',
      changes,
      '',
    ].join('\n'),
  };
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const release = prepareRelease(
    process.env.RELEASE_COMMIT,
    process.env.DEPLOYMENT_URL,
  );
  const notesPath = join(process.env.RUNNER_TEMP, 'frontend-release-notes.md');
  writeFileSync(notesPath, release.notes);
  appendFileSync(
    process.env.GITHUB_OUTPUT,
    `tag=${release.tag}\ntag_exists=${release.tagExists}\nnotes=${notesPath}\n`,
  );
  appendFileSync(
    process.env.GITHUB_STEP_SUMMARY,
    `## ${release.tag}\n\n${release.notes}`,
  );
}
