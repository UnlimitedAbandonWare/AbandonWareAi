# 공개 소스 복구 기준

GitHub 브랜치는 포트폴리오의 파일과 공개 기준점 사이의 차이를 보관합니다. 복구 시에는 고정 백업 브랜치의 커밋 SHA를 먼저 확인하고, 필요한 파일을 그 커밋에서 읽은 뒤 아래 Git blob ID를 대조합니다.

| 파일 | Git blob ID |
| --- | --- |
| `frontend/src/lib/bff.js` | `fd685b98061211228e865f5fdd9541136faedda2` |
| `frontend/test/bff.test.mjs` | `0ad59817682ef7e810fa5c5f1e1244f7c065609c` |

- [공개 기준 커밋](https://github.com/UnlimitedAbandonWare/AbandonWareAi/commit/b2eaba4679f70ded860b052faa59b29073d0c859)
- [포트폴리오 고정 백업 브랜치](https://github.com/UnlimitedAbandonWare/AbandonWareAi/tree/backup/portfolio-20261116)
- [독립 복구 테스트](../frontend/test/bff.test.mjs)를 실행해 복구한 소스의 동작을 확인합니다.
