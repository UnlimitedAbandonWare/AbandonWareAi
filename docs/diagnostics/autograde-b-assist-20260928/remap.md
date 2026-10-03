# P01–P23 live remap

기준 ZIP: Downloads `SOURCE_MAP.md` (2026-09-28). live 바이트가 줄번호보다 우선이다.
생성: `python -B scripts/autograde_b_rail.py --root .` (같은 시각의 B00 실측).

`sha=match`이면 ZIP 줄 범위가 그 파일에 아직 맞다.
`sha=STALE`이면 ZIP 줄을 패치 위치로 쓰지 말고 `live symbol line`을 연 뒤 메서드를 다시 읽는다. 그 줄은 ZIP 앵커에 가장 가까운 토큰 줄이다.

| ID | live line | zip lines | sha | live SHA-256 |
|---|---|---|---|---|
| P01 | 59 | 39-72 | match | `99730157e6d742dfc8ed738c0140187dab361c695fe83948177b83cb00f981c8` |
| P02 | - | 1-6 | match | `5c647cb81692df339ef336f4acfc5dcc4e169d879278616ebaf9973fad22d241` |
| P03 | 18 | 84-127 | STALE | `db0f684f7ae9abf3329b1e135ac0c02c31eef7f08a2b45e19a4c9e057bd90877` |
| P04 | 68 | 66-133 | match | `cb2b3427361127c472983b1c5b5f8d38dbba2e32d93a443954853dcc5e52f74e` |
| P05 | 91 | 98-125 | STALE | `548ed4cf1c69a0541221f9330ce20bfb2acce769719fc18ea5d3b5b49efca797` |
| P06 | 44 | 44-85 | match | `aea768e073a0b8fd2eec4ebcecdee8daced3872da090dc0200eb709cebcfbca2` |
| P07 | 2924 | 2902-2917 | STALE | `c7209fc10f2e5a98eceda8fd6ab9b1135fbe22d0d72809b1ac6e0bf453d33e4d` |
| P08 | 2973 | 2954-2971 | STALE | `c7209fc10f2e5a98eceda8fd6ab9b1135fbe22d0d72809b1ac6e0bf453d33e4d` |
| P09 | 3061 | 3036-3056 | STALE | `c7209fc10f2e5a98eceda8fd6ab9b1135fbe22d0d72809b1ac6e0bf453d33e4d` |
| P10 | 3154 | 3070-3102 | STALE | `c7209fc10f2e5a98eceda8fd6ab9b1135fbe22d0d72809b1ac6e0bf453d33e4d` |
| P11 | 3313 | 3245-3254 | STALE | `c7209fc10f2e5a98eceda8fd6ab9b1135fbe22d0d72809b1ac6e0bf453d33e4d` |
| P12 | 277 | 276-328 | STALE | `697ecfce1d37b5e15916f1c27ac3fe0758a855070703d7b57230fcb2426e2424` |
| P13 | 43 | 34-45 | match | `b3f5b17140747b63e1db7c27b5adb6d03e193ffedf11f7dd077368162924458f` |
| P14 | 7720 | 7630-7690 | STALE | `c7209fc10f2e5a98eceda8fd6ab9b1135fbe22d0d72809b1ac6e0bf453d33e4d` |
| P15 | 277 | 277-301 | match | `cf653582b3c89fd57e4db4c9e0ac374c8ff083afce8f60493b7f65133e3dffb1` |
| P16 | 219 | 187-225 | STALE | `b654249d52f6d2453de709929dade4cac658ddc68c49b6d87dd579da1efcb769` |
| P17 | 184 | 184-248 | match | `785b3b9cbfef6e72f6fe69838d30ad2c354d334a5ef2c48633aed82b2e524221` |
| P18 | 16 | 16-45 | match | `abbc5a39d555b857192e9ff3676a583df024b7eb1bcceb72a54abce21a75650c` |
| P19 | 675 | 674-739 | STALE | `bc27cabda7b2107b596b20ef67aaf41aac84272e5da8d63ca8b5990298ae7310` |
| P20 | 1774 | 1774-1823 | STALE | `6c97188bc4d9bb3d1f13df6b66c8064c3309e183830717f76e7d7e1ed43a8a43` |
| P21 | 38 | 38-82 | match | `5cf20792e28d40af3290f41ede8bcd6295859ac5c87965c58b1f8c899bed1278` |
| P22 | 34 | 27-43 | match | `743e90d890607f2ee71a1b7ec7ef889f42e2ead7fb0ae4b8bbfce8a9dd4e0650` |
| P23 | 24 | 19-34 | match | `f09a374e9c8b94e91fb318987bb1918d3847b89efd54e03104a9fec8841d2076` |

P07–P11과 P14는 같은 `ChatWorkflow.java`다. P14 정의에 가까운 줄은 7720이다. 4370 근처는 호출이다.

이번 픽은 B02 / P06이다. P06은 SHA match이고 `execute`는 44행이다.
