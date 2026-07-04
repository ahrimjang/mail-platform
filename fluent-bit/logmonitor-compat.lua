-- opensearch-log-analysis-backend(logmonitor) 대시보드가 기대하는 스키마로 레코드를 보강합니다.
-- LogstashEncoder 원본 필드(service, level=대문자, @timestamp, stack_trace)는 그대로 두고
-- logmonitor 조회/집계용 필드(serviceId, serviceName, level=소문자, timestamp, stackTrace, resolved)를 추가합니다.
--   · serviceId  : terms 집계 대상 (keyword 매핑 — opensearch/index-template.json)
--   · level      : logmonitor 는 소문자 "error"/"warning" 으로 term 질의 (WARN → warning 으로 변환)
--   · timestamp  : logmonitor 의 정렬/범위 필드 (@timestamp 값 복사)
--   · stackTrace : 워커가 예외 타입을 추출하는 필드 (stack_trace 를 복사)

local SERVICE_NAMES = {
    ["mail-api"]    = "메일 API",
    ["mail-worker"] = "메일 발송 워커",
    ["mail-admin"]  = "메일 어드민",
}

function logmonitor_compat(tag, timestamp, record)
    local svc = record["service"]
    if svc ~= nil then
        record["serviceId"] = svc
        record["serviceName"] = SERVICE_NAMES[svc] or svc
    end

    local lvl = record["level"]
    if lvl ~= nil then
        lvl = string.lower(lvl)
        if lvl == "warn" then
            lvl = "warning"
        end
        record["level"] = lvl
    end

    if record["timestamp"] == nil and record["@timestamp"] ~= nil then
        record["timestamp"] = record["@timestamp"]
    end

    if record["stackTrace"] == nil and record["stack_trace"] ~= nil then
        record["stackTrace"] = record["stack_trace"]
    end

    if record["resolved"] == nil then
        record["resolved"] = false
    end

    -- 2 = 레코드만 수정, 이벤트 타임스탬프는 유지
    return 2, timestamp, record
end
