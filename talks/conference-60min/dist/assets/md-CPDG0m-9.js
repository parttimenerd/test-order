import{$ as e,B as t,C as n,D as r,S as i,b as a,bt as o,v as s,vt as c,y as l}from"./modules/shiki-CdGmwcEL.js";import{nt as u,rt as d}from"./index-CfBNjXQS.js";import{t as f}from"./slidev/default-uT3wGax9.js";import{t as p}from"./slidev/CodeBlockWrapper-NOSHtXe7.js";import{t as m}from"./DemoCard-CL75gw-Z.js";var h={__name:`slides.md__slidev_4`,setup(h){let{$slidev:g,$nav:_,$clicksContext:v,$clicks:y,$page:b,$renderContext:x,$frontmatter:S}=d();return v.setup(),(d,h)=>{let g=p,_=m;return t(),l(f,o(r(c(u)(c(S),3))),{default:e(()=>[n(_,{id:`D0`,title:`What we do today`},{default:e(()=>[n(g,{title:``,ranges:[]},{default:e(()=>[...h[0]||=[s(`pre`,{class:`shiki shiki-themes vitesse-dark vitesse-light slidev-code`,style:{"--shiki-dark":`#dbd7caee`,"--shiki-light":`#393a34`,"--shiki-dark-bg":`#121212`,"--shiki-light-bg":`#ffffff`}},[s(`code`,{class:`language-bash`},[s(`span`,{class:`line`},[s(`span`,{style:{"--shiki-dark":`#B8A965`,"--shiki-light":`#998418`}},`cd`),s(`span`,{style:{"--shiki-dark":`#C98A7D`,"--shiki-light":`#B56959`}},` third-party/commons-collections`)]),i(`
`),s(`span`,{class:`line`},[s(`span`,{style:{"--shiki-dark":`#758575DD`,"--shiki-light":`#A0ADA0`}},`# patch already applied — one missing line in remove()`)]),i(`
`),s(`span`,{class:`line`},[s(`span`,{style:{"--shiki-dark":`#80A665`,"--shiki-light":`#59873A`}},`mvn`),s(`span`,{style:{"--shiki-dark":`#C98A7D`,"--shiki-light":`#B56959`}},` test`),s(`span`,{style:{"--shiki-dark":`#C99076`,"--shiki-light":`#A65E2B`}},` -Dtestorder.mode=skip`)])])],-1)]]),_:1})]),_:1}),a(`
DEMO D0 — commons-collections with enumerationiterator patch pre-applied. mode=skip forces alphabetical.

SETUP (run prepare-demo.sh before going on stage):
- patch already applied via make-change-commons.sh
- .test-order/ absent — mode=skip would ignore it anyway

STEPS:
1. cd third-party/commons-collections
2. mvn test -Dtestorder.mode=skip  — DO NOT kill it early, let it reach pos 237

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