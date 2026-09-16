// Standalone browser functional suite. See tests/plans/ui-functional.md.
// Uses only local fixtures; no application server, credentials or model calls.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const playwright = require('playwright');
const catalog = require('./catalog.json');
const resource = process.env.VISUALIZER_HTML || path.resolve(__dirname, '../../main/resources/com/patbaumgartner/embabel/workflow/visualizer/workflow-visualizer.html');
const html = fs.readFileSync(resource, 'utf8');
const artifacts = process.env.UI_ARTIFACTS || path.join(os.tmpdir(), 'visualizer-functional');
const engines = (process.env.UI_BROWSERS || 'chromium,firefox,webkit').split(',');
const cases = [];
const test = (id, name, run, options = {}) => cases.push({ id, name, run, options });
const copy = x => structuredClone(x);
const agent = () => copy(catalog.agents[0]);
const active = p => p.locator('.agent').first().locator('.view-panel:not([hidden])');
const box = p => active(p).locator('div.flow');
const svg = p => active(p).locator('svg.flow-svg');
const button = (p, name) => active(p).getByRole('button', { name: new RegExp(name) });
const tab = (p, name) => p.locator('.agent').first().getByRole('tab', { name, exact: true });
const height = p => box(p).evaluate(e => e.clientHeight);
const frame = p => p.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
const matrix = p => svg(p).evaluate(e => { const m=e.querySelector('g').transform.baseVal.consolidate().matrix; return {s:m.a,x:m.e,y:m.f}; });
const positions = p => active(p).locator('.node-g').evaluateAll(es => es.map(e=>e.getAttribute('transform')));
async function fit(p) { await button(p, '^Fit').click(); await frame(p); }
async function noOverflow(p) {
    assert(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'page overflows horizontally');
    assert(await p.locator('.agent:visible').evaluateAll(es => es.every((e,i)=>i===es.length-1 || e.getBoundingClientRect().bottom<=es[i+1].getBoundingClientRect().top)), 'agent panels overlap');
}
async function allInside(p, selector = '.node-g') {
    const failures = await box(p).evaluate((e,selector) => {
        const v=e.getBoundingClientRect();
        return [...e.querySelectorAll(selector)].filter(n=>{const r=n.getBoundingClientRect();return r.left<v.left-1||r.right>v.right+1||r.top<v.top-1||r.bottom>v.bottom+1}).map(n=>n.getAttribute('aria-label')||n.textContent);
    }, selector);
    assert.deepEqual(failures, [], 'graph content outside viewport');
}
async function drag(p, target, dx, dy) {
    await target.scrollIntoViewIfNeeded();
    const r=await target.boundingBox();
    const x=r.x+r.width/2, y=r.y+r.height/2;
    await p.mouse.move(x,y); await p.mouse.down();
    await p.mouse.move(x+dx,y+dy,{steps:8}); await p.mouse.up(); await frame(p);
}
async function shot(p,name) { await p.screenshot({path:path.join(artifacts,`${p.engine}-${name}.png`),fullPage:false}); }
function one(a) { return {agents:[a]}; }

test('D01','All sample agents, nodes, edges, costs and finite layouts',async p=>{
    assert.equal(await p.locator('.agent').count(),catalog.agents.length);
    for(let i=0;i<catalog.agents.length;i++) {
        const a=catalog.agents[i], panel=p.locator('.agent').nth(i);
        assert.equal(await panel.locator('.view-panel:not([hidden]) .node-g').count(),a.flow.nodes.length);
        const rows=panel.locator('.paths tbody tr');
        assert.equal(await rows.count(),a.flow.paths.length);
        for(let k=0;k<a.flow.paths.length;k++) {
            const route=a.flow.paths[k]; const row=rows.nth(k);
            assert((await row.textContent()).includes(route.goal));
            assert.equal(await row.evaluate(e=>e.classList.contains('cheapest')),route.cheapest);
            assert((await row.textContent()).includes(String(route.totalCost ?? '—')));
        }
        for(const name of ['Dependencies','Flow']) {
            await panel.getByRole('tab',{name,exact:true}).click();
            const view=panel.locator('.view-panel:not([hidden])');
            assert.equal(await view.locator('.node-g').count(),name==='Flow'?a.flow.nodes.length:a.steps.length);
            if(name==='Flow') assert.equal(await view.locator('path.edge').count(),a.flow.edges.length);
            await view.getByRole('button',{name:/^Fit/}).click();
            assert(await view.locator('svg').evaluate(e=>!e.outerHTML.match(/NaN|Infinity/)));
            const nodes=await view.locator('.node-g').evaluateAll(es=>es.map(e=>{const r=e.getBoundingClientRect();return {x:r.x,y:r.y,w:r.width,h:r.height}}));
            for(let j=0;j<nodes.length;j++) for(let k=j+1;k<nodes.length;k++) {
                const a=nodes[j],b=nodes[k];
                assert(a.x+a.w<=b.x+1||b.x+b.w<=a.x+1||a.y+a.h<=b.y+1||b.y+b.h<=a.y+1,`overlapping nodes in ${name} of ${catalog.agents[i].agentName}`);
            }
        }
    }
    await noOverflow(p);
});
test('D02','Empty catalog shows an empty state',async p=>{assert.match(await p.locator('#canvas').textContent(),/No Embabel/);assert.equal(await p.locator('#no-match').isVisible(),false)}, {data:{agents:[]}});
test('D03','Agent with no steps has an empty state',async p=>{assert.equal(await p.locator('.agent').count(),1);assert.equal(await p.locator('svg').count(),0)}, {data:()=>{const a=agent();a.steps=[];return one(a)}});
test('D04','Legacy dependency-only catalog keeps all controls usable',async p=>{
    assert.equal(await p.getByRole('tab').count(),0);await fit(p);await allInside(p);assert.equal(await active(p).locator('.view-resize').count(),1,'legacy canvas has no resizer');
}, {data:()=>{const a=agent();delete a.flow;return one(a)}});
test('D05','HTTP failure is visible and escaped',async p=>{assert.match(await p.locator('.error').textContent(),/HTTP 503/);}, {status:503});
test('D06','Malformed JSON is reported',async p=>{assert.match(await p.locator('.error').textContent(),/Failed to load workflows/);}, {body:'{not json'});
test('D07','Network failure is reported',async p=>{assert.match(await p.locator('.error').textContent(),/Failed to load workflows/);}, {abort:true});
test('D08','Slow loading remains usable and eventually renders',async p=>{assert.equal(await p.locator('.agent').count(),12);await p.locator('#theme-btn').click();}, {delay:120});
test('D09','Context prefix and trailing slash resolve the same API',async p=>{assert.equal(await p.locator('.agent').count(),12);assert.match(await p.locator('#endpoint-hint').textContent(),/\/prefix\/custom\/api/);}, {url:'/prefix/custom/'});
test('D10','Catalog text cannot execute HTML or script',async p=>{
    assert.equal(await p.evaluate(()=>window.injected),undefined);assert.equal(await p.locator('#evil').count(),0);assert((await p.locator('.agent').textContent()).includes('<img'));
}, {data:()=>{const a=agent();a.agentName='<img id="evil" src=x onerror="window.injected=true">';a.description='<script>window.injected=true</script>';a.steps[0].description=a.agentName;return one(a)}});
test('D11','Long labels and metadata fit their cards',async p=>{
    await tab(p,'Dependencies').click();
    const clipped=await active(p).locator('foreignObject').evaluateAll(es=>es.flatMap(e=>{const r=e.getBoundingClientRect();return [...e.querySelectorAll('.nfo, .nfo *')].filter(n=>{const c=n.getBoundingClientRect();return c.top<r.top-2||c.bottom>r.bottom+2||c.left<r.left-2||c.right>r.right+2}).map(n=>n.textContent)}));
    assert.deepEqual(clipped,[]);
    const escapedText=await active(p).locator('foreignObject').evaluateAll(es=>es.flatMap(e=>{
        const bounds=e.getBoundingClientRect(),walker=document.createTreeWalker(e,NodeFilter.SHOW_TEXT),escaped=[];
        for(let node=walker.nextNode();node;node=walker.nextNode()) {
            const range=document.createRange();range.selectNodeContents(node);
            if([...range.getClientRects()].some(r=>r.right>bounds.right+2||r.left<bounds.left-2||r.bottom>bounds.bottom+2))escaped.push(node.textContent);
        }
        return escaped;
    }));
    assert.deepEqual(escapedText,[],'painted text overflows its foreignObject');
    await noOverflow(p);await fit(p);await shot(p,'metadata');
}, {data:()=>{const a=agent();a.description='Description '.repeat(60);a.steps[0].name='VeryLongUnbrokenMethodName'.repeat(12);a.steps[0].description='Long description '.repeat(100);a.steps[0].tags=['tag'.repeat(100)];a.steps[0].exportedRemote=true;a.steps[0].exportName='VeryLongExport'.repeat(20);a.steps[0].examples=['Example '.repeat(30), 'UnbrokenExample'.repeat(40)];return one(a)}});
test('D12','No route, component and detached nodes remain understandable',async p=>{
    const component=p.locator('.agent').filter({hasText:'ResearchUtils'});assert.match(await component.locator('.flow-warn').textContent(),/EmbabelComponent/);assert(await component.locator('.node-g').count()>0);
});
test('D13','Truncated and many routes disclose correctly',async p=>{
    assert.match(await active(p).locator('.flow-warn').textContent(),/first 8 routes/);
    assert.equal(await active(p).locator('details').getAttribute('open'),null);
    await active(p).locator('summary').click();assert.equal(await active(p).locator('tbody tr:visible').count(),8);
    await active(p).locator('summary').press('Enter');assert.equal(await active(p).locator('tbody tr:visible').count(),0);
}, {data:()=>{const a=agent();a.flow.paths=Array.from({length:8},()=>copy(a.flow.paths[0]));a.flow.truncated=true;return one(a)}});
test('D14','Empty flow layout remains finite and dependency fallback works',async p=>{await fit(p);assert(!(await svg(p).getAttribute('style')||'').match(/NaN|Infinity/));await tab(p,'Dependencies').click();await fit(p);await allInside(p)}, {data:()=>{const a=agent();a.flow={nodes:[],edges:[],paths:[],entryTypes:[],truncated:false};return one(a)}});

test('D15','All dependency metadata fields and badges render without clipping',async p=>{
    await tab(p,'Dependencies').click();const card=active(p).locator('.nfo').filter({has:p.locator('.nname', {hasText:'assessClean'})});const text=await card.textContent();
    for(const value of ['actualMethod','alternateBinding','AlternateOutput','ready','request.valid','done','retryExpression','EXPONENTIAL','dynamicCost','dynamicValue','0.17','2.5','tool description','categoryValue','explicitTool','metaKey','metaValue','StartingInput','ProvidedContext','namedInput','exampleValue','tagValue','remoteName','canRerun','readOnly','clears BB','triggerValue','returnDirect','not local','planner']) assert(text.includes(value),`missing metadata: ${value}`);
    const clipped=await active(p).locator('foreignObject').evaluateAll(es=>es.some(e=>e.firstElementChild.getBoundingClientRect().height>e.getBoundingClientRect().height+2));assert.equal(clipped,false);
    await shot(p,'full-metadata');
}, {data:()=>{const a=agent();Object.assign(a.steps[0],{method:'actualMethod',outputBinding:'alternateBinding',possibleOutputs:['AlternateOutput'],pre:['ready','spel:request.valid'],post:['done'],retryPolicy:'retryExpression',actionRetryPolicy:'EXPONENTIAL',costMethod:'dynamicCost',valueMethod:'dynamicValue',conditionCost:.17,goalValue:2.5,llmTool:true,llmToolDescription:'tool description',llmToolCategory:'categoryValue',llmToolName:'explicitTool',llmToolMetadata:[{key:'metaKey',value:'metaValue'}],exportStartingInputTypes:['StartingInput'],providedInputs:['ProvidedContext'],nameMatchInputs:['namedInput'],examples:['exampleValue'],tags:['tagValue'],goal:true,exportedRemote:true,exportName:'remoteName',canRerun:true,readOnly:true,clearBlackboard:true,trigger:'triggerValue',llmToolReturnDirect:true,exportedLocal:false,plannerGenerated:true});return one(a)}});
test('D16','Optional inputs, dynamic costs and unknown costs display correctly',async p=>{
    assert((await active(p).textContent()).includes('ClauseScreening?'));const rows=active(p).locator('tbody tr');assert.match(await rows.first().textContent(),/dynamic/);assert((await rows.nth(1).textContent()).includes('—'));assert.match(await active(p).locator('.flow-note').textContent(),/Cost method/);
}, {data:()=>{const a=agent();a.steps[0].optionalInputs=['ClauseScreening'];a.steps[0].costMethod='runtimeCost';a.flow.paths[0].dynamicCost=true;a.flow.paths[1].totalCost=null;return one(a)}});
test('D17','Dependency edges retain distinct substring labels and deduplicate exact labels',async p=>{
    const text=await active(p).locator('.edge-lbl').allTextContents();assert.deepEqual(text,['OrderId, Order']);
}, {data:()=>{const a=agent();a.steps=[{name:'produce',type:'Action',inputs:[],pre:[],post:[],output:'OrderId',possibleOutputs:['Order','OrderId']},{name:'consume',type:'Action',inputs:['OrderId','Order'],pre:[],post:[],output:'Receipt'}];a.flow=null;return one(a)}});

test('N01','Tab click, arrows, Home/End and ARIA agree',async p=>{
    const f=tab(p,'Flow'), d=tab(p,'Dependencies');await f.focus();
    for(const [key,expected] of [['ArrowRight',d],['ArrowRight',f],['End',d],['Home',f],['ArrowLeft',d]]) {
        await p.keyboard.press(key);assert.equal(await expected.getAttribute('aria-selected'),'true');assert.equal(await expected.getAttribute('tabindex'),'0');assert(await expected.evaluate(e=>e===document.activeElement));
        assert.equal(await p.locator('.agent').first().locator('.view-panel:visible').count(),1);
    }
    await f.click();assert.equal(await d.getAttribute('aria-selected'),'false');
});
test('N02','Filter supports name/class/planner/description and clear',async p=>{
    for(const term of [' complianceReviewAgent ','HYBRID','regulatory','com.patbaumgartner.embabel.compliance']) {
        await p.locator('#agent-filter').fill(term);assert(await p.locator('.agent:visible').count()>0);assert.equal(await p.locator('#no-match').isVisible(),false);
    }
    await p.locator('#agent-filter').fill('no such agent 123');assert.equal(await p.locator('.agent:visible').count(),0);assert.equal(await p.locator('#no-match').isVisible(),true);assert.match(await p.locator('#agent-count').textContent(),/0 of 12/);
    await p.locator('#agent-filter').fill('  ');assert.equal(await p.locator('.agent:visible').count(),12);
});
test('N03','Filter hide/show preserves view, size and moved layout',async p=>{
    await tab(p,'Dependencies').click();await active(p).locator('.view-resize').focus();await p.keyboard.press('ArrowDown');
    const h=await height(p);const node=active(p).locator('.node-g').first();await node.focus();await p.keyboard.press('ArrowRight');const pos=await positions(p);
    await p.locator('#agent-filter').fill('zzzz');await p.locator('#agent-filter').fill('');await frame(p);
    assert.equal(await tab(p,'Dependencies').getAttribute('aria-selected'),'true');assert.equal(await height(p),h);assert.deepEqual(await positions(p),pos);
});
test('N04','Theme persists after reload and labels match',async p=>{
    await p.locator('#theme-btn').click();assert.equal(await p.locator('html').getAttribute('data-theme'),'dark');assert.equal(await p.locator('#theme-btn').getAttribute('aria-pressed'),'true');
    await p.reload();await p.locator('.agent').first().waitFor();assert.equal(await p.locator('html').getAttribute('data-theme'),'dark');
    await p.locator('#theme-btn').click();assert.equal(await p.locator('html').getAttribute('data-theme'),'light');
});
test('N05','System theme changes update colors and toggle state',async p=>{
    await p.emulateMedia({colorScheme:'dark'});await frame(p);assert.equal(await p.locator('#theme-btn').getAttribute('aria-pressed'),'true');
    await p.emulateMedia({colorScheme:'light'});await frame(p);assert.equal(await p.locator('#theme-btn').getAttribute('aria-pressed'),'false');
});
test('N06','Denied local storage does not break theme or render',async p=>{await p.locator('#theme-btn').click();assert.equal(await p.locator('html').getAttribute('data-theme'),'dark');assert.equal(await p.locator('.agent').count(),12)}, {storageDenied:true});
test('N07','Invalid saved theme follows the system',async p=>{await p.emulateMedia({colorScheme:'dark'});await frame(p);assert.equal(await p.locator('#theme-btn').getAttribute('aria-pressed'),'true');}, {theme:'invalid'});

test('I01','Both views share the larger initial height',async p=>{
    const h=await height(p);await tab(p,'Dependencies').click();assert.equal(await height(p),h);
    const expected=await p.locator('.agent').first().locator('div.flow').evaluateAll(es=>Math.round(Math.max(240,Math.min(Math.max(...es.map(e=>Number(e.dataset.naturalHeight))),Math.min(innerHeight*.72,660))))-2);
    assert.equal(h,expected);
});
test('I02','Resize either view grows/shrinks without overlap or cross-agent changes',async p=>{
    const other=await p.locator('.agent').nth(1).locator('div.flow').first().evaluate(e=>e.clientHeight);
    for(const name of ['Dependencies','Flow']) {
        await tab(p,name).click();const h=await height(p);await drag(p,active(p).locator('.view-resize'),0,90);assert.equal(await height(p),h+90);await noOverflow(p);
        await drag(p,active(p).locator('.view-resize'),0,-70);assert.equal(await height(p),h+20);
    }
    assert.equal(await p.locator('.agent').nth(1).locator('div.flow').first().evaluate(e=>e.clientHeight),other);
});
test('I03','Keyboard resize, minimum and cancellation are stable',async p=>{
    const g=active(p).locator('.view-resize');await g.focus();await p.keyboard.press('Home');assert.equal(await height(p),238);
    await p.keyboard.press('ArrowUp');assert.equal(await height(p),238);await p.keyboard.press('Shift+ArrowDown');assert.equal(await height(p),338);
    assert.equal(await g.getAttribute('aria-valuenow'),'340');
    await g.scrollIntoViewIfNeeded();const r=await g.boundingBox();await p.mouse.move(r.x+40,r.y+7);await p.mouse.down();
    await g.dispatchEvent('pointercancel',{pointerId:1});const h=await height(p);
    await p.mouse.move(r.x+40,r.y+80);await p.mouse.up();assert.equal(await height(p),h);
});
test('I04','Fit and double-click fit include nodes/labels and clear scroll',async p=>{
    for(const name of ['Flow','Dependencies']) {
        await tab(p,name).click();await button(p,'Zoom In').click();await box(p).evaluate(e=>{e.scrollTop=300;e.scrollLeft=400});await fit(p);await allInside(p,'.node-g, .edge-lbl');assert.deepEqual(await box(p).evaluate(e=>[e.scrollLeft,e.scrollTop]),[0,0]);
        await button(p,'Zoom In').click();await svg(p).dblclick({position:{x:8,y:8}});await frame(p);await allInside(p,'.node-g, .edge-lbl');
    }
});
test('I05','Zoom in/out are reversible with finite clamp limits',async p=>{
    await fit(p);const before=await matrix(p);await button(p,'Zoom In').click();assert((await matrix(p)).s>before.s);await button(p,'Zoom Out').click();assert(Math.abs((await matrix(p)).s-before.s)<.0002);
    for(let i=0;i<28;i++) await button(p,'Zoom In').click();assert((await matrix(p)).s<=8);
    for(let i=0;i<50;i++) await button(p,'Zoom Out').click();assert((await matrix(p)).s>=.05);
});
test('I06','Zoom stays anchored to the scrolled viewport center',async p=>{
    await box(p).evaluate(e=>{e.scrollLeft=250;e.scrollTop=30});
    const anchor=async()=>{const m=await matrix(p);const v=await box(p).evaluate(e=>({x:e.scrollLeft+e.clientWidth/2,y:e.scrollTop+e.clientHeight/2}));return {x:(v.x-m.x)/m.s,y:(v.y-m.y)/m.s}};
    const a=await anchor();await button(p,'Zoom In').click();const b=await anchor();assert(Math.abs(a.x-b.x)<2 && Math.abs(a.y-b.y)<2,'zoom moved the viewport anchor');
});
test('I07','Plain wheel scrolls; Ctrl-wheel zooms about the cursor',async p=>{
    await fit(p);const m=await matrix(p);const r=await box(p).boundingBox();await p.mouse.move(r.x+70,r.y+70);await p.mouse.wheel(0,100);await frame(p);assert.equal((await matrix(p)).s,m.s);
    await p.keyboard.down('Control');await p.mouse.wheel(0,-100);await p.keyboard.up('Control');await frame(p);assert((await matrix(p)).s>m.s);
});
test('I08','Background pan changes viewport without moving nodes',async p=>{
    const pos=await positions(p),m=await matrix(p);const r=await box(p).boundingBox();await p.mouse.move(r.x+10,r.y+10);await p.mouse.down();await p.mouse.move(r.x+85,r.y+45,{steps:5});await p.mouse.up();
    assert.deepEqual(await positions(p),pos);const n=await matrix(p);assert(Math.abs(n.x-m.x-75)<1);assert(Math.abs(n.y-m.y-35)<1);
});
test('I09','Mouse node drag updates connected edges; Reset restores everything',async p=>{
    await fit(p);const pos=await positions(p),paths=await active(p).locator('path.edge').evaluateAll(es=>es.map(e=>e.getAttribute('d')));
    await drag(p,active(p).locator('.node-hit').first(),55,45);assert.notDeepEqual(await positions(p),pos);assert.notDeepEqual(await active(p).locator('path.edge').evaluateAll(es=>es.map(e=>e.getAttribute('d'))),paths);
    await button(p,'Reset Layout').click();await frame(p);assert.deepEqual(await positions(p),pos);assert.deepEqual(await active(p).locator('path.edge').evaluateAll(es=>es.map(e=>e.getAttribute('d'))),paths);assert.equal((await matrix(p)).s,1);
});
test('I10','Keyboard node moves stay visible beyond original canvas bounds',async p=>{
    const n=active(p).locator('.node-g').first();await n.focus();
    for(let i=0;i<40;i++) await p.keyboard.press('Shift+ArrowRight');
    const visible=await n.evaluate(e=>{const n=e.getBoundingClientRect(),v=e.closest('div.flow').getBoundingClientRect();return n.left>=v.left&&n.right<=v.right&&n.top>=v.top&&n.bottom<=v.bottom});
    assert(visible,'keyboard-moved node is clipped outside viewport');
});
test('I11','Negative-coordinate moves and Fit recover the entire graph',async p=>{
    const n=active(p).locator('.node-g').first();await n.focus();for(let i=0;i<15;i++) await p.keyboard.press('Shift+ArrowLeft');for(let i=0;i<12;i++) await p.keyboard.press('Shift+ArrowUp');await fit(p);await allInside(p,'.node-g, .edge-lbl');
});
test('I12','Node hover/focus spotlight clears and restores correctly',async p=>{
    await fit(p);const n=active(p).locator('.node-g').first();await n.hover();assert(await svg(p).evaluate(e=>e.classList.contains('has-hover')));await p.mouse.move(0,0);assert.equal(await active(p).locator('.node-hi').count(),0);
    await n.focus();await p.mouse.move(0,0);assert(await active(p).locator('.node-hi').count()>0);await tab(p,'Flow').focus();assert.equal(await active(p).locator('.node-hi').count(),0);
});
test('I13','Route hover lights exactly its edges',async p=>{
    const rows=active(p).locator('tbody tr');
    for(let k=0;k<2;k++){await rows.nth(k).hover();assert.equal(await active(p).locator('path.path-hi').count(),catalog.agents[0].flow.edges.filter(e=>e.paths.includes(k)).length)}
    await p.mouse.move(0,0);assert.equal(await active(p).locator('.path-hi').count(),0);
});
test('I14','Focused route keeps its highlight when mouse leaves',async p=>{
    const row=active(p).locator('tbody tr').first();await row.focus();await row.hover();await p.mouse.move(0,0);assert(await active(p).locator('path.path-hi').count()>0,'route focus lost its highlight on mouseleave');
    await tab(p,'Flow').focus();assert.equal(await active(p).locator('path.path-hi').count(),0);
});
test('I15','Resizing a zoomed SVG updates scroll extent',async p=>{
    await button(p,'Zoom In').click();await button(p,'Zoom In').click();await active(p).locator('.view-resize').focus();await p.keyboard.press('Home');await frame(p);assert((await box(p).evaluate(e=>e.scrollHeight))>=await height(p));
    await p.setViewportSize({width:768,height:600});await frame(p);await noOverflow(p);await fit(p);await allInside(p);
});

for(const width of [320,390,768,1440,1920]) test(`R${width}` ,`Responsive layout at ${width}px in both themes`,async p=>{
    await noOverflow(p);await fit(p);await allInside(p);await shot(p,`light-${width}`);await p.locator('#theme-btn').click();await shot(p,`dark-${width}`);
    await tab(p,'Dependencies').click();await noOverflow(p);await fit(p);await allInside(p);await shot(p,`dependencies-${width}`);
}, {viewport:{width,height:900}});
test('R01','Short viewport and enlarged text do not overlap panels',async p=>{
    await p.addStyleTag({content:'html { font-size: 200%; }'});await p.setViewportSize({width:800,height:450});await noOverflow(p);
    await tab(p,'Dependencies').click();await fit(p);await allInside(p);
});
test('R02','All controls are named, IDs unique, hidden panels not focusable',async p=>{
    const ids=await p.locator('[id]').evaluateAll(es=>es.map(e=>e.id));assert.equal(new Set(ids).size,ids.length);
    for(const b of await p.locator('button').all()) assert((await b.getAttribute('aria-label'))||(await b.textContent()).trim());
    const node=active(p).locator('.node-g').first();assert(await node.getAttribute('aria-label'));assert.equal(await node.getAttribute('tabindex'),'0');
    assert.equal(await p.locator('.view-panel[hidden] .node-g:visible').count(),0);
});
test('R03','Touch horizontal pan moves graph and vertical gesture leaves it alone',async p=>{
    const before=await matrix(p);
    const gesture=async(dx,dy)=>svg(p).evaluate((e,{dx,dy})=>{
        const r=e.getBoundingClientRect(),x=r.x+80,y=r.y+80;
        function send(type,points) {
            // Handler contract only: WebKit does not expose a constructible Touch.
            // T01 separately exercises native Chromium touchscreen gestures.
            const event=new Event(type,{bubbles:true,cancelable:true});
            Object.defineProperty(event,'touches',{value:points});e.dispatchEvent(event);
        }
        send('touchstart',[{clientX:x,clientY:y}]);
        send('touchmove',[{clientX:x+dx,clientY:y+dy}]);
        send('touchend',[]);
    },{dx,dy});
    await gesture(-65,4);assert(Math.abs((await matrix(p)).x-before.x+65)<1);const mid=await matrix(p);await gesture(4,-65);assert.deepEqual(await matrix(p),mid);
}, {touch:true,viewport:{width:390,height:844}});
test('R04','Stress catalog stays finite, searchable and recoverable',async p=>{
    assert.equal(await p.locator('.node-g').count(),120);await fit(p);await allInside(p);await p.locator('#agent-filter').fill('missing');await p.locator('#agent-filter').fill('');await frame(p);await fit(p);await allInside(p);await noOverflow(p);
}, {data:()=>{const a=agent();a.flow=null;a.steps=Array.from({length:120},(_,i)=>({...copy(a.steps[0]),name:`step${i}`,inputs:i?[`Type${i-1}`]:[],output:`Type${i}`,pre:[],post:[]}));return one(a)}});
test('R05','Repeated render and filter do not leave stale content',async p=>{
    for(let i=0;i<8;i++) {await p.evaluate(c=>render(c),one(agent()));await frame(p);await p.locator('#agent-filter').fill('zz');await p.locator('#agent-filter').fill('');}
    assert.equal(await p.locator('.agent').count(),1);assert.equal(await p.locator('.node-g').count(),14);await fit(p);await allInside(p);
});

test('R06','Scaled card text paints inside its SVG card in both themes',async p=>{
    await tab(p,'Dependencies').click();
    for(const theme of ['light','dark']) {
        if(theme==='dark') await p.locator('#theme-btn').click();
        await fit(p);await p.mouse.move(0,0);await box(p).scrollIntoViewIfNeeded();
        const bounds=await box(p).evaluate(e=>{
            return [...e.querySelectorAll('.node-card')].map(n=>{
                const r=n.getBoundingClientRect();return {left:r.left-2,right:r.right+2,top:r.top-2,bottom:r.bottom+2};
            });
        });
        const before=await p.screenshot({animations:'disabled'});
        const id=await active(p).getAttribute('id');
        const hide=await p.addStyleTag({content:`#${id} foreignObject .nfo { visibility: hidden !important; }`});
        const after=await p.screenshot({animations:'disabled'});await hide.evaluate(e=>e.remove());
        fs.writeFileSync(path.join(artifacts,`${p.engine}-paint-${theme}-before.png`),before);
        fs.writeFileSync(path.join(artifacts,`${p.engine}-paint-${theme}-hidden.png`),after);
        const pixels=await p.evaluate(async({before,after,bounds})=>{
            async function decode(data) {
                const img=new Image();img.src='data:image/png;base64,'+data;await img.decode();
                const canvas=document.createElement('canvas');canvas.width=img.width;canvas.height=img.height;
                const ctx=canvas.getContext('2d');ctx.drawImage(img,0,0);return {width:img.width,pixels:ctx.getImageData(0,0,img.width,img.height).data};
            }
            const a=await decode(before),b=await decode(after);let changed=0,outside=0;const samples=[];
            for(let i=0;i<a.pixels.length;i+=4) {
                if(Math.max(Math.abs(a.pixels[i]-b.pixels[i]),Math.abs(a.pixels[i+1]-b.pixels[i+1]),Math.abs(a.pixels[i+2]-b.pixels[i+2]))<8)continue;
                changed++;const x=(i/4)%a.width,y=Math.floor(i/4/a.width);
                if(!bounds.some(r=>x>=r.left&&x<=r.right&&y>=r.top&&y<=r.bottom)){outside++;if(samples.length<10)samples.push({x,y});}
            }
            return {changed,outside,samples,bounds};
        },{before:before.toString('base64'),after:after.toString('base64'),bounds});
        assert(pixels.changed>100,'paint check did not observe rendered card text');
        assert.equal(pixels.outside,0,`${theme}: card text painted outside node rectangles ${JSON.stringify(pixels)}`);
        await shot(p,`paint-${theme}`);
    }
}, {viewport:{width:768,height:900}});

test('I16','Focused nodes stay visible after native scrolling',async p=>{
    await tab(p,'Dependencies').click();await box(p).evaluate(e=>{e.scrollLeft=500;e.scrollTop=150});
    const n=active(p).locator('.node-g').first();await n.focus();await p.keyboard.press('ArrowRight');
    assert(await n.evaluate(e=>{const n=e.getBoundingClientRect(),v=e.closest('div.flow').getBoundingClientRect();return n.left>=v.left-1&&n.right<=v.right+1&&n.top>=v.top-1&&n.bottom<=v.bottom+1}));
});
test('I17','Tab changes preserve each graph layout and remain independent by agent',async p=>{
    await fit(p);const m=await matrix(p);const n=active(p).locator('.node-g').first();await n.focus();await p.keyboard.press('ArrowRight');const pos=await positions(p);
    await tab(p,'Dependencies').click();await fit(p);await tab(p,'Flow').click();assert.deepEqual(await positions(p),pos);assert.equal((await matrix(p)).s,m.s);
    assert.equal(await p.locator('.agent').nth(1).getByRole('tab',{name:'Flow',exact:true}).getAttribute('aria-selected'),'true');
});
test('I18','Layout and edge labels survive loops and detached-node dragging',async p=>{
    const i=catalog.agents.findIndex(a=>a.flow.edges.some(e=>e.loop));assert(i>=0);
    await p.locator('#agent-filter').fill(catalog.agents[i].agentName);const panel=p.locator('.agent:visible');
    await panel.getByRole('button',{name:/^Fit/}).click();await shot(p,'loop');
    const edges=await panel.locator('path.edge').evaluateAll(es=>es.map(e=>e.getAttribute('d')));
    assert(edges.every(e=>e&&!/NaN|Infinity/.test(e)));
    await p.locator('#agent-filter').fill('ResearchUtils');const component=p.locator('.agent:visible');await component.getByRole('button',{name:/^Fit/}).click();await shot(p,'detached');
});
test('I19','Window blur ends node dragging and panning',async p=>{
    await fit(p);const n=active(p).locator('.node-hit').first();await n.scrollIntoViewIfNeeded();let r=await n.boundingBox();
    await p.mouse.move(r.x+r.width/2,r.y+r.height/2);await p.mouse.down();await p.mouse.move(r.x+r.width/2+25,r.y+r.height/2+20);
    await p.evaluate(()=>window.dispatchEvent(new Event('blur')));const pos=await positions(p);await p.mouse.move(r.x+100,r.y+100);await p.mouse.up();assert.deepEqual(await positions(p),pos);assert.equal(await active(p).locator('.dragging').count(),0);
    r=await box(p).boundingBox();await p.mouse.move(r.x+8,r.y+8);await p.mouse.down();await p.evaluate(()=>window.dispatchEvent(new Event('blur')));const m=await matrix(p);await p.mouse.move(r.x+75,r.y+35);await p.mouse.up();assert.deepEqual(await matrix(p),m);
});
test('T01','Native touchscreen pan, page scroll and resize',async p=>{
    const session=await p.context().newCDPSession(p);
    const gesture=async(x,y,dx,dy)=>{
        await session.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});
        for(let i=1;i<=8;i++) await session.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x+dx*i/8,y:y+dy*i/8}]});
        await session.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await frame(p);
    };
    await box(p).scrollIntoViewIfNeeded();let r=await box(p).boundingBox();const before=await matrix(p);
    await gesture(r.x+120,Math.min(r.y+100,600),-70,2);assert((await matrix(p)).x<before.x-30,'native touch did not pan');
    const beforeScroll=await p.evaluate(()=>scrollY);r=await box(p).boundingBox();
    await gesture(r.x+120,Math.min(r.y+150,600),0,-90);await p.waitForFunction(y=>scrollY>y+10,beforeScroll);
    const g=active(p).locator('.view-resize');await g.scrollIntoViewIfNeeded();r=await g.boundingBox();const h=await height(p);
    await gesture(r.x+100,r.y+7,0,60);assert.equal(await height(p),h+60);await session.detach();
}, {engines:['chromium'],touch:true,viewport:{width:390,height:844}});

async function runCase(browser, engine, c) {
    const started=Date.now(), opts=c.options;
    const context=await browser.newContext({viewport:opts.viewport||{width:1440,height:1000},colorScheme:'light',hasTouch:!!opts.touch});
    const p=await context.newPage();p.engine=engine;p.setDefaultTimeout(6000);
    const errors=[];p.on('pageerror',e=>errors.push(e.message));
    try {
        if(opts.storageDenied) await p.addInitScript(()=>{Storage.prototype.getItem=()=>{throw new Error('storage denied')};Storage.prototype.setItem=()=>{throw new Error('storage denied')}});
        if(opts.theme) await p.addInitScript(theme=>localStorage.setItem('embabel-theme',theme),opts.theme);
        const url=opts.url||'/test/embabel-workflows';const api=url.replace(/\/+$/,'')+'/api';
        await p.route('**/*',async r=>{
            const pathname=new URL(r.request().url()).pathname;
            if(pathname===api) {
                if(opts.abort) return r.abort();
                if(opts.delay) await new Promise(resolve=>setTimeout(resolve,opts.delay));
                const data=typeof opts.data==='function'?opts.data():opts.data||catalog;
                return r.fulfill({status:opts.status||200,contentType:'application/json',body:opts.body||JSON.stringify(data)});
            }
            if(pathname===url) return r.fulfill({contentType:'text/html',body:html});
            return r.fulfill({status:404,body:''});
        });
        await p.goto('http://visualizer.test'+url);
        await p.waitForFunction(()=>!!document.querySelector('#canvas .agent, #canvas .empty') && !document.querySelector('#canvas').textContent.includes('Loading workflows'));
        await frame(p);
        await c.run(p,engine);await frame(p);assert.deepEqual(errors,[],'uncaught browser errors');
        return {engine,id:c.id,name:c.name,result:'PASS',ms:Date.now()-started};
    } catch(e) {
        await p.screenshot({path:path.join(artifacts,`${engine}-${c.id}-failure.png`)}).catch(()=>{});
        return {engine,id:c.id,name:c.name,result:'FAIL',ms:Date.now()-started,error:e.message,errors};
    } finally {await context.close();}
}
async function main() {
    fs.mkdirSync(artifacts,{recursive:true});
    const selected=cases.filter(c=>!process.env.UI_TEST||new RegExp(process.env.UI_TEST).test(c.id));
    assert(selected.length,'no matching test cases');const results=[],versions={};
    for(const engine of engines) {
        let browser;
        try {browser=await playwright[engine].launch({headless:true,executablePath:process.env['UI_'+engine.toUpperCase()+'_EXECUTABLE']});versions[engine]=browser.version();}
        catch(e){for(const c of selected.filter(c=>!c.options.engines||c.options.engines.includes(engine))) results.push({engine,id:c.id,name:c.name,result:'BLOCKED',error:e.message});console.error(`${engine}: ${e.message.split('\n')[0]}`);continue;}
        try {for(const c of selected.filter(c=>!c.options.engines||c.options.engines.includes(engine))) {const r=await runCase(browser,engine,c);results.push(r);console.log(`${engine} ${r.id} ${r.result} ${r.name}${r.error?'\n  '+r.error.split('\n').slice(0,5).join('\n  '):''}`)}}
        finally {await browser.close();}
    }
    const report={timestamp:new Date().toISOString(),sourceSha256:crypto.createHash('sha256').update(html).digest('hex'),node:process.version,playwright:require('playwright/package.json').version,versions,results};
    fs.writeFileSync(path.join(artifacts,'results.json'),JSON.stringify(report,null,2)+'\n');
    const failures=results.filter(r=>r.result!=='PASS');console.log(`${results.length-failures.length}/${results.length} passed`);
    if(failures.length) process.exitCode=1;else console.log('FUNCTIONAL SUITE PASSED');
}
if(require.main===module) main().catch(e=>{console.error(e);process.exitCode=1});
module.exports={cases};
