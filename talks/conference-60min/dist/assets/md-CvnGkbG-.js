import{$ as e,B as t,C as n,D as r,S as i,b as a,bt as o,v as s,vt as c,y as l}from"./modules/shiki-CdGmwcEL.js";import{nt as u,rt as d}from"./index-CfBNjXQS.js";import{t as f}from"./slidev/default-uT3wGax9.js";import{t as p}from"./slidev/CodeBlockWrapper-NOSHtXe7.js";var m={__name:`slides.md__slidev_54`,setup(m){let{$slidev:h,$nav:g,$clicksContext:_,$clicks:v,$page:y,$renderContext:b,$frontmatter:x}=d();return _.setup(),(d,m)=>{let h=p;return t(),l(f,o(r(c(u)(c(x),53))),{default:e(()=>[m[1]||=s(`h2`,null,[s(`code`,null,`mvn test-order:detect-dependencies`)],-1),n(h,{title:``,ranges:[]},{default:e(()=>[...m[0]||=[s(`pre`,{class:`shiki shiki-themes vitesse-dark vitesse-light slidev-code`,style:{"--shiki-dark":`#dbd7caee`,"--shiki-light":`#393a34`,"--shiki-dark-bg":`#121212`,"--shiki-light-bg":`#ffffff`}},[s(`code`,{class:`language-text`},[s(`span`,{class:`line`},[s(`span`,null,`[test-order] Generating Tuscan square for 47 tests...`)]),i(`
`),s(`span`,{class:`line`},[s(`span`,null,`[test-order] Running 47 orderings...`)]),i(`
`),s(`span`,{class:`line`},[s(`span`,null,`[test-order] Potential victims: UserServiceTest, CartTest`)]),i(`
`),s(`span`,{class:`line`},[s(`span`,null,`[test-order] Running isolation probes...`)]),i(`
`),s(`span`,{class:`line`},[s(`span`,null,`[test-order] FOUND: CartTest is a victim — polluted by UserServiceTest`)]),i(`
`),s(`span`,{class:`line`},[s(`span`,null,`[test-order] Suggested fix: CartTest.tearDown() should reset UserRepository`)])])],-1)]]),_:1}),a(`
The goal runs the full Tuscan + exclusion probe algorithm.
Output names the victim, the polluter, and suggests a fix location.
"Run this before a release. Not on every commit — it's expensive. If it finds something, fix the test."
TRANSITION: "So — where does this leave us?"
`)]),_:1},16)}}};export{m as default};