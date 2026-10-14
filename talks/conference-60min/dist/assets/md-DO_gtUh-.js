import{L as e,T as t,V as n,X as r,_ as i,_t as a,b as o,g as s,ht as c,v as l,x as u}from"./modules/shiki-DMRAbjcN.js";import{et as d,tt as f}from"./index-DpRp6j57.js";import{t as p}from"./slidev/default-BqOWARef.js";var m={__name:`slides.md__slidev_57`,setup(m){let{$slidev:h,$nav:g,$clicksContext:_,$clicks:v,$page:y,$renderContext:b,$frontmatter:x}=f();return _.setup(),(f,m)=>{let h=n(`CodeBlockWrapper`);return e(),i(p,a(t(c(d)(c(x),56))),{default:r(()=>[m[1]||=s(`h2`,null,[s(`code`,null,`mvn test-order:detect-dependencies`)],-1),u(h,{title:``,ranges:[]},{default:r(()=>[...m[0]||=[s(`pre`,{class:`shiki shiki-themes vitesse-dark vitesse-light slidev-code`,style:{"--shiki-dark":`#dbd7caee`,"--shiki-light":`#393a34`,"--shiki-dark-bg":`#121212`,"--shiki-light-bg":`#ffffff`}},[s(`code`,{class:`language-text`},[s(`span`,{class:`line`},[s(`span`,null,`[test-order] Generating Tuscan square for 47 tests...`)]),o(`
`),s(`span`,{class:`line`},[s(`span`,null,`[test-order] Running 47 orderings...`)]),o(`
`),s(`span`,{class:`line`},[s(`span`,null,`[test-order] Potential victims: UserServiceTest, CartTest`)]),o(`
`),s(`span`,{class:`line`},[s(`span`,null,`[test-order] Running isolation probes...`)]),o(`
`),s(`span`,{class:`line`},[s(`span`,null,`[test-order] FOUND: CartTest is a victim — polluted by UserServiceTest`)]),o(`
`),s(`span`,{class:`line`},[s(`span`,null,`[test-order] Suggested fix: CartTest.tearDown() should reset UserRepository`)])])],-1)]]),_:1}),l(`
The goal runs the full Tuscan + exclusion probe algorithm.
Output names the victim, the polluter, and suggests a fix location.
"Run this before a release. Not on every commit — it's expensive. If it finds something, fix the test."
TRANSITION: "So — where does this leave us?"
`)]),_:1},16)}}};export{m as default};