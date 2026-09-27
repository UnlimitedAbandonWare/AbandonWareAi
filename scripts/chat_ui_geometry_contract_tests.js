const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const baseUrl = process.env.CHAT_UI_BASE_URL || "http://127.0.0.1:18183/chat-ui";
const session = `chat-ui-geometry-${process.pid}`;
const artifactRoot = path.resolve("output", "playwright");
const npxCli = path.join(
    process.env.ProgramFiles || "C:\\Program Files",
    "nodejs",
    "node_modules",
    "npm",
    "bin",
    "npx-cli.js"
);
if (!fs.existsSync(npxCli)) {
    throw new Error("Node.js npx-cli.js is required for the Playwright geometry contract");
}
fs.mkdirSync(artifactRoot, { recursive: true });

function cli(...args) {
    const result = spawnSync(
        process.execPath,
        [npxCli, "--yes", "--package=@playwright/cli@latest", "playwright-cli", "--session", session, ...args],
        { cwd: artifactRoot, encoding: "utf8", windowsHide: true }
    );
    const output = `${result.stdout || ""}${result.stderr || ""}`;
    if (result.status !== 0) {
        throw new Error(`playwright-cli failed (${result.status}): ${result.error || output.trim()}`);
    }
    return output;
}

function parseEval(output) {
    const match = output.match(/### Result\r?\n("(?:\\.|[^"\\])*")/);
    if (!match) {
        throw new Error(`missing Playwright evaluation result: ${output.trim()}`);
    }
    return JSON.parse(JSON.parse(match[1]));
}

function assertGeometry(measurement) {
    const failures = [];
    if (measurement.composerBottom > measurement.wrapperBottom + 1) {
        failures.push("composer escapes wrapper");
    }
    if (measurement.inputBottom > measurement.viewportHeight + 1) {
        failures.push("input is below viewport");
    }
    if (measurement.documentScrollWidth > measurement.viewportWidth + 1) {
        failures.push("document overflows horizontally");
    }
    if (!measurement.inputHitTestable) {
        failures.push("input center is not hit-testable");
    }
    if (measurement.chatWindowOverflowY !== "auto") {
        failures.push("chat window is not the single vertical scroll owner");
    }
    if (measurement.wrapperScrollHeight > measurement.wrapperClientHeight + 1) {
        failures.push("chat chrome overflows the fixed wrapper");
    }
    if (!measurement.decisionRect) {
        failures.push("Decision ribbon is not measurable");
    } else if (
        measurement.decisionRect.left < -1 ||
        measurement.decisionRect.right > measurement.viewportWidth + 1 ||
        measurement.decisionRect.bottom > measurement.wrapperBottom + 1
    ) {
        failures.push("Decision ribbon escapes the visible chat wrapper");
    }
    if (measurement.decisionStages.length !== 6) {
        failures.push("Decision ribbon does not expose six stages");
    }
    for (const stage of measurement.decisionStages) {
        if (!stage.text) failures.push(`${stage.stage || "unknown"} has no readable text`);
        if (stage.rect.left < -1 || stage.rect.right > measurement.viewportWidth + 1) {
            failures.push(`${stage.stage || "unknown"} clips outside the viewport`);
        }
        if (stage.clipped) failures.push(`${stage.stage || "unknown"} text is clipped`);
    }
    if (measurement.viewportWidth <= 760) {
        if (measurement.topBarRect.height > 100) {
            failures.push(`mobile utility bar is too tall (${measurement.topBarRect.height.toFixed(1)}px)`);
        }
        if (measurement.statusRailRect.height > 252) {
            failures.push(`mobile status rail is too tall (${measurement.statusRailRect.height.toFixed(1)}px)`);
        }
        if (measurement.decisionRect?.height > 112) {
            failures.push(`mobile Decision ribbon is too tall (${measurement.decisionRect.height.toFixed(1)}px)`);
        }
        if (measurement.viewportHeight >= 600 && measurement.chatWindowRect.height < 176) {
            failures.push(`mobile transcript is too shallow (${measurement.chatWindowRect.height.toFixed(1)}px)`);
        }
        if (measurement.statusPills.length !== 7 || measurement.statusPills.some((pill) => !pill.visible)) {
            failures.push("mobile status rail does not preserve all seven visible pills");
        }
        for (const item of measurement.topBarItems) {
            if (item.clipped) failures.push(`${item.name} clips inside the mobile utility bar`);
            if (
                item.rect.left < measurement.topBarRect.left - 1 ||
                item.rect.right > measurement.topBarRect.right + 1 ||
                item.rect.top < measurement.topBarRect.top - 1 ||
                item.rect.bottom > measurement.topBarRect.bottom + 1
            ) {
                failures.push(`${item.name} escapes the mobile utility bar`);
            }
        }
        for (const control of measurement.topBarInteractive) {
            if (control.height < 43.5) failures.push(`${control.name} is below the 44px touch target`);
        }
        if (measurement.topBarItemOverlap) {
            failures.push("mobile utility bar items overlap");
        }
        if (
            !measurement.adminMenuRect ||
            measurement.adminMenuRect.top < measurement.topBarRect.bottom + 4 ||
            measurement.adminMenuRect.left < -1 ||
            measurement.adminMenuRect.right > measurement.viewportWidth + 1
        ) {
            failures.push("mobile Admin tools menu is not anchored below the utility bar");
        }
    }
    if (failures.length > 0) {
        throw new Error(`${measurement.viewportWidth}x${measurement.viewportHeight}: ${failures.join(", ")}`);
    }
}

function assertOpenDiagnosticsGeometry(measurement) {
    const failures = [];
    const shortViewportReflow = measurement.viewportWidth <= 760 && measurement.viewportHeight <= 880;
    if (!shortViewportReflow && Math.abs(measurement.wrapperClientHeight - measurement.closedWrapperClientHeight) > 1) {
        failures.push("opening Diagnostics changes the fixed wrapper height");
    }
    if (measurement.composerBottom > measurement.wrapperBottom + 1) {
        failures.push("composer escapes wrapper while Diagnostics is open");
    }
    if (measurement.inputBottom > measurement.viewportHeight + 1) {
        failures.push("input is below viewport while Diagnostics is open");
    }
    if (measurement.wrapperScrollHeight > measurement.wrapperClientHeight + 1) {
        failures.push("open Diagnostics overflows the fixed wrapper");
    }
    if (!measurement.inputHitTestable) {
        failures.push("input center is not hit-testable while Diagnostics is open");
    }
    if (measurement.diagnosticsOverflowY !== "auto" || measurement.diagnosticsHeight <= 0) {
        failures.push("Diagnostics detail is not a usable internal scroll owner");
    }
    if (
        measurement.diagnosticsTop < measurement.disclosureTop - 1 ||
        measurement.diagnosticsBottom > measurement.disclosureBottom + 1
    ) {
        failures.push("Diagnostics detail is clipped by its disclosure");
    }
    if (measurement.diagnosticsVisibleHeight < Math.min(32, measurement.diagnosticsHeight)) {
        failures.push("Diagnostics detail has no usable visible scroll area");
    }
    if (!measurement.diagnosticsOpen) {
        failures.push("Diagnostics did not remain open for geometry measurement");
    }
    if (failures.length > 0) {
        throw new Error(`${measurement.viewportWidth}x${measurement.viewportHeight} open Diagnostics: ${failures.join(", ")}`);
    }
}

function assertStoppedTerminalGeometry(measurement) {
    const failures = [];
    if (measurement.composerBottom > measurement.wrapperBottom + 1) {
        failures.push("composer escapes wrapper");
    }
    if (measurement.wrapperScrollHeight > measurement.wrapperClientHeight + 1) {
        failures.push("chat chrome overflows the fixed wrapper");
    }
    if (!measurement.inputHitTestable) {
        failures.push("input center is not hit-testable");
    }
    if (measurement.diagnosticsSummary !== "Response stopped · user cancelled") {
        failures.push("stopped primary diagnostic is not visible");
    }
    if (failures.length > 0) {
        throw new Error(`390x700 stopped-terminal: ${failures.join(", ")}`);
    }
}

function assertZoomEquivalentGeometry(measurement) {
    try {
        assertGeometry(measurement);
    } catch (error) {
        throw new Error(`200%-zoom-equivalent ${error.message}`);
    }
}

function assertSelectionEntropyGeometry(measurement) {
    const failures = [];
    if (!measurement?.cardRect) failures.push("Selection replay card is not measurable");
    if (measurement.rowPairCount !== 12 || measurement.visibleRowCount !== 24) {
        failures.push("Selection replay does not preserve all 12 visible definition rows");
    }
    if (measurement.ariaLabel !== "Selection replay: Not requested" || measurement.ariaHidden !== null) {
        failures.push("Selection replay accessible naming changed");
    }
    if (measurement.cardRect?.height > 320) {
        failures.push(`Selection replay card is too tall (${measurement.cardRect.height.toFixed(1)}px)`);
    }
    if (measurement.cardRect?.width < measurement.transcriptRect.width * 0.75) {
        failures.push("Selection replay card does not use the available transcript width");
    }
    if (
        measurement.cardRect?.left < measurement.transcriptRect.left - 1 ||
        measurement.cardRect?.right > measurement.transcriptRect.right + 1 ||
        measurement.documentScrollWidth > measurement.viewportWidth + 1
    ) {
        failures.push("Selection replay card overflows the conversation surface");
    }
    if (measurement.clippedRowCount > 0) failures.push("Selection replay row text is clipped");
    if (["auto", "scroll"].includes(measurement.cardOverflowY) || ["auto", "scroll"].includes(measurement.listOverflowY)) {
        failures.push("Selection replay introduces a nested vertical scroll owner");
    }
    if (failures.length > 0) {
        throw new Error(`390x700 selection-replay: ${failures.join(", ")}`);
    }
}

const viewports = [
    { width: 320, height: 760 },
    { width: 375, height: 900 },
    { width: 390, height: 700 },
    { width: 639, height: 900 },
    { width: 760, height: 900 },
    { width: 1024, height: 900 },
    { width: 1440, height: 900 }
];
const zoomEquivalentViewport = { width: 640, height: 450 };
const measurements = [];
const openDiagnosticsMeasurements = [];
let stoppedTerminalMeasurement = null;
let zoomEquivalentMeasurement = null;
let selectionEntropyMeasurement = null;

try {
    cli("open", baseUrl);
    for (const viewport of [...viewports, zoomEquivalentViewport]) {
        cli("resize", String(viewport.width), String(viewport.height));
        cli("eval", "window.scrollTo(0, document.documentElement.scrollHeight)");
        const wrapper = parseEval(cli(
            "eval",
            "JSON.stringify((() => {" +
                "const node=document.querySelector('.chat-area-wrapper');" +
                "return {rect:node.getBoundingClientRect().toJSON(),scrollHeight:node.scrollHeight,clientHeight:node.clientHeight};" +
            "})())"
        ));
        const composer = parseEval(cli("eval", "JSON.stringify(document.querySelector('.composer').getBoundingClientRect().toJSON())"));
        const input = parseEval(cli("eval", "JSON.stringify(document.querySelector('#messageInput').getBoundingClientRect().toJSON())"));
        const page = parseEval(cli("eval", "JSON.stringify({viewportWidth:innerWidth,viewportHeight:innerHeight,documentScrollWidth:document.documentElement.scrollWidth,scrollY:window.scrollY})"));
        const inputHitTestable = parseEval(cli("eval", "JSON.stringify((() => { const i=document.querySelector('#messageInput'); const r=i.getBoundingClientRect(); const h=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2); return h===i||i.contains(h); })())"));
        const wrapperOverflowY = parseEval(cli("eval", "JSON.stringify(getComputedStyle(document.querySelector('.chat-area-wrapper')).overflowY)"));
        const chatWindowOverflowY = parseEval(cli("eval", "JSON.stringify(getComputedStyle(document.querySelector('#chatWindow')).overflowY)"));
        const decision = parseEval(cli(
            "eval",
            "JSON.stringify((() => {" +
                "const root=document.querySelector('#decisionRibbon');" +
                "const rect=root?.getBoundingClientRect();" +
                "return {" +
                    "rect:rect?rect.toJSON():null," +
                    "stages:Array.from(document.querySelectorAll('#decisionRibbon [data-decision-stage]')).map((node)=>({" +
                        "stage:node.dataset.decisionStage," +
                        "rect:node.getBoundingClientRect().toJSON()," +
                        "text:(node.textContent||'').trim()," +
                        "clipped:node.scrollWidth>node.clientWidth+1||node.scrollHeight>node.clientHeight+1" +
                    "}))" +
                "};" +
            "})())"
        ));
        const compactChrome = parseEval(cli(
            "eval",
            "JSON.stringify((() => {" +
                "const rect=(node)=>node.getBoundingClientRect().toJSON();" +
                "const clipped=(node)=>node.scrollWidth>node.clientWidth+1||node.scrollHeight>node.clientHeight+1;" +
                "const overlaps=(a,b)=>Math.max(0,Math.min(a.right,b.right)-Math.max(a.left,b.left))*Math.max(0,Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top))>1;" +
                "const topBar=document.querySelector('.top-utility-bar');" +
                "const topBarNodes=[['brand',document.querySelector('.brand-mark')],['navigation',document.querySelector('.app-menu-bar')],['model',document.querySelector('[data-current-model]')]];" +
                "const topBarItems=topBarNodes.map(([name,node])=>({name,rect:rect(node),clipped:clipped(node)}));" +
                "const visibleControls=Array.from(document.querySelectorAll('.top-utility-bar a,.top-utility-bar summary')).filter((node)=>node.getClientRects().length>0);" +
                "const topBarInteractive=visibleControls.map((node)=>({name:(node.textContent||node.getAttribute('aria-label')||node.tagName).trim(),height:rect(node).height}));" +
                "const topBarItemOverlap=topBarItems.some((item,index)=>topBarItems.slice(index+1).some((other)=>overlaps(item.rect,other.rect)));" +
                "const adminTools=document.querySelector('.admin-tools');" +
                "adminTools.open=true;" +
                "const adminMenuRect=rect(document.querySelector('.admin-tools-menu'));" +
                "adminTools.open=false;" +
                "const statusRail=document.querySelector('.status-rail');" +
                "const statusPills=Array.from(document.querySelectorAll('.status-pill')).map((node)=>({visible:node.getClientRects().length>0,rect:rect(node)}));" +
                "return {topBarRect:rect(topBar),topBarItems,topBarInteractive,topBarItemOverlap,adminMenuRect,statusRailRect:rect(statusRail),statusPills,chatWindowRect:rect(document.querySelector('#chatWindow'))};" +
            "})())"
        ));
        const measurement = {
            ...page,
            scrollY: page.scrollY,
            wrapperTop: wrapper.rect.top,
            wrapperBottom: wrapper.rect.bottom,
            wrapperScrollHeight: wrapper.scrollHeight,
            wrapperClientHeight: wrapper.clientHeight,
            composerTop: composer.top,
            composerBottom: composer.bottom,
            inputTop: input.top,
            inputBottom: input.bottom,
            inputHitTestable,
            wrapperOverflowY,
            chatWindowOverflowY,
            decisionRect: decision.rect,
            decisionStages: decision.stages,
            ...compactChrome
        };
        if (viewport === zoomEquivalentViewport) {
            zoomEquivalentMeasurement = measurement;
        } else {
            measurements.push(measurement);
        }
        process.stdout.write(`${JSON.stringify(measurement)}\n`);
        if (viewport !== zoomEquivalentViewport) {
            cli("eval", "document.querySelector('[data-testid=\"chat-diagnostics\"]').open=true");
            cli("eval", "window.scrollTo(0, document.documentElement.scrollHeight)");
            const openDiagnostics = parseEval(cli(
                "eval",
                "JSON.stringify((() => {" +
                    "const wrapper=document.querySelector('.chat-area-wrapper');" +
                    "const composer=document.querySelector('.composer');" +
                    "const input=document.querySelector('#messageInput');" +
                    "const detail=document.querySelector('.diagnostics-stack');" +
                    "const disclosure=document.querySelector('[data-testid=\"chat-diagnostics\"]');" +
                    "const wrapperRect=wrapper.getBoundingClientRect();" +
                    "const composerRect=composer.getBoundingClientRect();" +
                    "const inputRect=input.getBoundingClientRect();" +
                    "const detailRect=detail.getBoundingClientRect();" +
                    "const disclosureRect=disclosure.getBoundingClientRect();" +
                    "const hit=document.elementFromPoint(inputRect.left+inputRect.width/2,inputRect.top+inputRect.height/2);" +
                    "const diagnosticsVisibleHeight=Math.max(0,Math.min(detailRect.bottom,disclosureRect.bottom)-Math.max(detailRect.top,disclosureRect.top));" +
                    "return {" +
                        "scenario:'open-diagnostics',viewportWidth:innerWidth,viewportHeight:innerHeight," +
                        "wrapperBottom:wrapperRect.bottom,wrapperScrollHeight:wrapper.scrollHeight,wrapperClientHeight:wrapper.clientHeight," +
                        "composerBottom:composerRect.bottom,inputBottom:inputRect.bottom,inputHitTestable:hit===input||input.contains(hit)," +
                        "diagnosticsOpen:disclosure.open===true,diagnosticsHeight:detailRect.height,diagnosticsTop:detailRect.top,diagnosticsBottom:detailRect.bottom," +
                        "diagnosticsVisibleHeight,disclosureTop:disclosureRect.top,disclosureBottom:disclosureRect.bottom," +
                        "diagnosticsOverflowY:getComputedStyle(detail).overflowY" +
                    "};" +
                "})())"
            ));
            openDiagnostics.closedWrapperClientHeight = wrapper.clientHeight;
            openDiagnosticsMeasurements.push(openDiagnostics);
            process.stdout.write(`${JSON.stringify(openDiagnostics)}\n`);
            assertOpenDiagnosticsGeometry(openDiagnostics);
            cli("eval", "document.querySelector('[data-testid=\"chat-diagnostics\"]').open=false");
        }
    }
    cli("resize", "390", "700");
    cli(
        "eval",
        "(() => {" +
            "syncCurrentTurnHealthOverlay('stopped','geometry-fixture',{streamStopped:true});" +
            "renderDecisionRibbon(presentObservedDecision({streamStatus:'stopped',healthOverlay:currentTurnHealthOverlay}));" +
            "renderPrimaryDiagnostic(selectPrimaryDiagnostic({healthOverlay:currentTurnHealthOverlay,streamStatus:'stopped',isStopAvailable:false}));" +
            "return true;" +
        "})()"
    );
    cli("eval", "window.scrollTo(0, document.documentElement.scrollHeight)");
    stoppedTerminalMeasurement = parseEval(cli(
        "eval",
        "JSON.stringify((() => {" +
            "const wrapper=document.querySelector('.chat-area-wrapper');" +
            "const composer=document.querySelector('.composer');" +
            "const input=document.querySelector('#messageInput');" +
            "const wrapperRect=wrapper.getBoundingClientRect();" +
            "const composerRect=composer.getBoundingClientRect();" +
            "const inputRect=input.getBoundingClientRect();" +
            "const hit=document.elementFromPoint(inputRect.left+inputRect.width/2,inputRect.top+inputRect.height/2);" +
            "return {" +
                "scenario:'stopped-terminal'," +
                "viewportWidth:innerWidth," +
                "viewportHeight:innerHeight," +
                "wrapperBottom:wrapperRect.bottom," +
                "wrapperScrollHeight:wrapper.scrollHeight," +
                "wrapperClientHeight:wrapper.clientHeight," +
                "composerBottom:composerRect.bottom," +
                "inputHitTestable:hit===input||input.contains(hit)," +
                "diagnosticsSummary:(document.querySelector('#diagnosticsSummary').textContent||'').trim()" +
            "};" +
        "})())"
    ));
    process.stdout.write(`${JSON.stringify(stoppedTerminalMeasurement)}\n`);
    cli(
        "eval",
        "(() => {" +
            "const chatWindow=document.querySelector('#chatWindow');" +
            "const bubble=document.createElement('div');" +
            "bubble.className='message assistant';" +
            "bubble.dataset.state='ready';" +
            "bubble.textContent='Answer';" +
            "chatWindow.replaceChildren(bubble);" +
            "renderSelectionEntropyTrace({selectionEntropySignal:{" +
                "schema:'awx.selection-entropy.v1',mode:'standard',replayAccepted:false,coherenceStatus:'not_requested'," +
                "replayReference:null,algorithmVersion:'selection-entropy-v1',decisionDigest:'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'," +
                "decisionCount:0,drawCount:0,stableTieBreakCount:0,candidateDriftCount:0,routerDrawCount:0,strategyDrawCount:0,ensembleDrawCount:0," +
                "completionOrderDeterministic:false,reasonCode:''" +
            "}},bubble);" +
            "return true;" +
        "})()"
    );
    selectionEntropyMeasurement = parseEval(cli(
        "eval",
        "JSON.stringify((() => {" +
            "const transcript=document.querySelector('#chatWindow');" +
            "const card=transcript.querySelector('[data-selection-entropy-card]');" +
            "const list=card?.querySelector('dl');" +
            "const rows=Array.from(list?.children||[]);" +
            "return {" +
                "scenario:'selection-replay',viewportWidth:innerWidth,documentScrollWidth:document.documentElement.scrollWidth," +
                "transcriptRect:transcript.getBoundingClientRect().toJSON(),cardRect:card?.getBoundingClientRect().toJSON()||null," +
                "rowPairCount:rows.length/2,visibleRowCount:rows.filter((node)=>node.getClientRects().length>0).length," +
                "clippedRowCount:rows.filter((node)=>node.scrollWidth>node.clientWidth+1||node.scrollHeight>node.clientHeight+1).length," +
                "ariaLabel:card?.getAttribute('aria-label')||null,ariaHidden:card?.getAttribute('aria-hidden')??null," +
                "cardOverflowY:card?getComputedStyle(card).overflowY:null,listOverflowY:list?getComputedStyle(list).overflowY:null" +
            "};" +
        "})())"
    ));
    process.stdout.write(`${JSON.stringify(selectionEntropyMeasurement)}\n`);
} finally {
    try {
        cli("close");
    } catch (_) {
        // A failed assertion must not be hidden by best-effort browser cleanup.
    }
}

for (const measurement of measurements) {
    assertGeometry(measurement);
}
assertStoppedTerminalGeometry(stoppedTerminalMeasurement);
assertZoomEquivalentGeometry(zoomEquivalentMeasurement);
assertSelectionEntropyGeometry(selectionEntropyMeasurement);
process.stdout.write("chat-ui stopped-terminal geometry contract passed (390x700)\n");
process.stdout.write("chat-ui zoom-equivalent geometry contract passed (640x450)\n");
process.stdout.write("chat-ui selection-replay geometry contract passed (390x700)\n");
process.stdout.write(`chat-ui open-Diagnostics geometry contracts passed (${openDiagnosticsMeasurements.length}/${viewports.length})\n`);
process.stdout.write(`chat-ui geometry contracts passed (${measurements.length}/${viewports.length})\n`);
