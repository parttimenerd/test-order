import{_ as s}from"./DemoCard-Dqz1oY4p.js";import{o as m,b as i,w as d,g as e,d as p,v as l,x as c,C as n}from"./modules/vue-S1F8S-Jh.js";import{I as u}from"./slidev/default-D8rpuaCo.js";import{u as h,f as _}from"./slidev/context-PTAtwiRM.js";import"./index-COT5_59P.js";import"./modules/shiki-DYMu1dN8.js";const j={__name:"slides.md__slidev_18",setup(f){const{$clicksContext:r,$frontmatter:o}=h();return r.setup(),(w,t)=>{const a=s;return m(),i(u,l(c(n(_)(n(o),17))),{default:d(()=>[t[0]||(t[0]=e("h1",null,"Demo 1: learn → order → rank shift",-1)),p(a,{id:"D1",duration:"6 min",cmd:`cd samples/sample-shop
mvn test                  # learn run
mvn test                  # order run
mvn test-order:show       # scores + why
# --- now make a change ---
# Add: if (item == null) throw new IllegalArgumentException();
$EDITOR src/main/java/com/example/shop/Cart.java
mvn test
mvn test-order:show       # watch CartTest jump to #1`,title:"Two runs. Then one edit. Watch the rank respond.",watch:"Run 1: index written. Run 2: order changed, APFD printed. Edit: CartTest jumps to #1 — changed-test=9, overlap=2."}),t[1]||(t[1]=e("div",{class:"pt-3 text-sm opacity-50 text-center"},[e("em",null,"Switch to terminal tab #1.")],-1))]),_:1},16)}}};export{j as default};
