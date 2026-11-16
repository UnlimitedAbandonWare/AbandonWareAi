# 공개 소스 복구 기준

GitHub 브랜치는 포트폴리오의 파일과 공개 기준점 사이의 차이를 보관합니다. 복구 시에는 고정 백업 브랜치의 커밋 SHA를 먼저 확인하고, 필요한 파일을 그 커밋에서 읽은 뒤 아래 Git blob ID를 대조합니다.

| 파일 | Git blob ID |
| --- | --- |
| `frontend/src/lib/bff.js` | `fd685b98061211228e865f5fdd9541136faedda2` |
| `frontend/test/bff.test.mjs` | `0ad59817682ef7e810fa5c5f1e1244f7c065609c` |

- [공개 기준 커밋](https://github.com/UnlimitedAbandonWare/AbandonWareAi/commit/b2eaba4679f70ded860b052faa59b29073d0c859)
- [포트폴리오 고정 백업 브랜치](https://github.com/UnlimitedAbandonWare/AbandonWareAi/tree/backup/portfolio-20261116)
- [독립 복구 테스트](../frontend/test/bff.test.mjs)를 실행해 복구한 소스의 동작을 확인합니다.
- [포트폴리오와 Git 가드 최초 `main` 반영 커밋](https://github.com/UnlimitedAbandonWare/AbandonWareAi/commit/b1cd5a7389bbb659b3c193b3d0f7358b82acfc2d)은 두 변경을 함께 복구할 때 사용합니다.
- 이후 공개의 복구 기준은 각 게시 커밋의 첫 번째 부모 SHA입니다. 해당 SHA를 보존하고 필요한 파일을 복구한 뒤 새 커밋으로 반영합니다.
- [정기 게시 운영 절차](../docs/kb/github-publication-schedule.md)에서 승인된 후보를 게시하는 조건과 실행 기록을 확인할 수 있습니다.
- 예약 실행에서 기준 변경을 보고하면 먼저 GitHub에서 후보가 이미 반영됐는지 확인합니다. 이미 반영된 변경은 같은 내용의 새 커밋을 만들지 않습니다.
