import{L as e,T as t,V as n,X as r,_ as i,_t as a,b as o,g as s,ht as c,v as l,x as u}from"./modules/shiki-DMRAbjcN.js";import{et as d,tt as f}from"./index-DpRp6j57.js";import{t as p}from"./slidev/default-BqOWARef.js";import{t as m}from"./DemoCard-RFfUV7gU.js";var h={__name:`slides.md__slidev_4`,setup(h){let{$slidev:g,$nav:_,$clicksContext:v,$clicks:y,$page:b,$renderContext:x,$frontmatter:S}=f();return v.setup(),(f,h)=>{let g=n(`CodeBlockWrapper`),_=m;return e(),i(p,a(t(c(d)(c(S),3))),{default:r(()=>[u(_,{id:`D0`,title:`What we do today`},{default:r(()=>[u(g,{title:``,ranges:[]},{default:r(()=>[...h[0]||=[s(`pre`,{class:`shiki shiki-themes vitesse-dark vitesse-light slidev-code`,style:{"--shiki-dark":`#dbd7caee`,"--shiki-light":`#393a34`,"--shiki-dark-bg":`#121212`,"--shiki-light-bg":`#ffffff`}},[s(`code`,{class:`language-bash`},[s(`span`,{class:`line`},[s(`span`,{style:{"--shiki-dark":`#B8A965`,"--shiki-light":`#998418`}},`cd`),s(`span`,{style:{"--shiki-dark":`#C98A7D`,"--shiki-light":`#B56959`}},` third-party/commons-collections`)]),o(`
`),s(`span`,{class:`line`},[s(`span`,{style:{"--shiki-dark":`#80A665`,"--shiki-light":`#59873A`}},`./make-change-commons.sh`),s(`span`,{style:{"--shiki-dark":`#758575DD`,"--shiki-light":`#A0ADA0`}},`   # remove one line from remove()`)]),o(`
`),s(`span`,{class:`line`},[s(`span`,{style:{"--shiki-dark":`#80A665`,"--shiki-light":`#59873A`}},`mvn`),s(`span`,{style:{"--shiki-dark":`#C98A7D`,"--shiki-light":`#B56959`}},` test`)])])],-1)]]),_:1})]),_:1}),l(`
DEMO D0 — commons-collections, NO test-order. prepare-demo.sh ensures .mvn/extensions.xml is absent.

SETUP (run prepare-demo.sh before going on stage):
- Source is clean — make-change-commons.sh applies the patch live on stage
- .test-order/ absent, .mvn/extensions.xml has no test-order plugin

STEPS:
1. cd third-party/commons-collections
2. mvn test  — DO NOT kill it early, let it reach pos 237

NARRATE as it runs:
- "This is Apache Commons Collections. Real open-source project. 321 tests."
- "I removed one line from EnumerationIterator.remove() — a \`last = null\` reset."
- "Alphabetical order. A… B… C… E… we're at test 80 out of 321… still going…"
- Keep narrating as it crawls past 150, 200, 237
- When EnumerationIteratorTest fails: "There it is. Position 237. We ran 236 tests first."
- "Every one of those was wasted time."

Let it sink in for 2 seconds. Then advance.

FALLBACK: asciinema play public/demo-d0.cast
`)]),_:1},16)}}};export{h as default};