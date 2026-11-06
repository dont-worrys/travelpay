// 송금 조회 부하 측정 스크립트입니다. 목록(GET /api/v1/transfers)과 상세(GET /api/v1/transfers/{id})를 번갈아 요청합니다.
// 조회는 다른 서비스를 부르지 않고 payment DB만 읽습니다. 송금 요청 측정(transfer-request.js)을 먼저 돌려 송금 내역이 쌓인 상태에서 잽니다.
//
// 이 스크립트만의 환경 변수
//   QUERY list   : 목록만 (보낸 내역 첫 페이지, size 20)
//         detail : 상세만
//         mixed  : 반씩 (기본)

import http from 'k6/http';
import exec from 'k6/execution';
import { BASE_URL, count, currentPhase, summarize } from './k6-common.js';

export { options } from './k6-common.js';

const QUERY = __ENV.QUERY || 'mixed';
const DATA = JSON.parse(open('./data/users.json'));

/** 송금인마다 상세 조회에 쓸 송금 ID를 하나씩 미리 찾는다 */
export function setup() {
    const detailIds = DATA.pairs.map((pair) => {
        const response = http.get(`${BASE_URL}/api/v1/transfers?direction=SENT&size=1`, { headers: headers(pair.senderId) });
        const items = response.status === 200 ? response.json('data.items') : [];
        return items && items.length ? items[0].transferId : null;
    });
    if (QUERY !== 'list' && detailIds.some((id) => id === null)) {
        throw new Error('상세 조회에 쓸 송금이 없는 송금인이 있습니다. transfer-request.js 측정을 먼저 실행하세요.');
    }
    return { detailIds };
}

export default function (setupData) {
    const vu = exec.vu.idInTest;
    const index = (vu - 1) % DATA.pairs.length;
    const senderId = DATA.pairs[index].senderId;
    const phase = currentPhase();
    const useList = QUERY === 'list' || (QUERY === 'mixed' && exec.vu.iterationInScenario % 2 === 0);
    const response = useList
        ? http.get(`${BASE_URL}/api/v1/transfers?direction=SENT&page=0&size=20`,
            { headers: headers(senderId), tags: { phase, name: 'GET /api/v1/transfers' } })
        : http.get(`${BASE_URL}/api/v1/transfers/${setupData.detailIds[index]}`,
            { headers: headers(senderId), tags: { phase, name: 'GET /api/v1/transfers/{id}' } });
    count(response, phase, response.status === 200);
}

function headers(userId) {
    return { 'User-Id': userId, 'User-Role': 'USER' };
}

export function handleSummary(data) {
    return summarize(data, `송금 조회(${QUERY})`, { query: QUERY });
}
