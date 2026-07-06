# Research & related work

test-order isn't a brand-new idea — it stands on roughly two decades of academic work on **test case prioritization** (TCP) and **regression test selection** (RTS), and on industry experience from companies running hundreds of millions of tests a day.

This page is a small bibliography linking the techniques in test-order back to the papers and posts that informed them. If you'd rather see the practical side first, start with [Getting started](GETTING_STARTED.mdx) or [Architecture](ARCHITECTURE.md); come back here when you want the *why*.

## Foundational surveys

- **Yoo, S., & Harman, M. (2012).** *Regression testing minimization, selection and prioritization: a survey.* Software Testing, Verification and Reliability, 22(2), 67–120.
  [DOI: 10.1002/stvr.430](https://doi.org/10.1002/stvr.430) · [Wiley](https://onlinelibrary.wiley.com/doi/abs/10.1002/stvr.430)
  The canonical TCP/RTS survey. Frames the trade-off between cost (CPU time) and effectiveness (fault-finding). test-order's [scoring model](SCORING.md) targets the same objective: surface the bug-catching tests first.

- **Bertolino, A. (2007).** *Software Testing Research: Achievements, Challenges, Dreams.* Future of Software Engineering (FOSE '07), ICSE 2007.
  [DOI: 10.1109/FOSE.2007.25](https://doi.org/10.1109/FOSE.2007.25) · [IEEE](https://ieeexplore.ieee.org/document/4221614/)
  Wider-context survey of where automated software testing is heading; the section on "smarter test execution" maps directly onto what TCP/RTS tools (test-order included) try to deliver.

## Industrial-scale prioritization

- **Memon, A., Gao, Z., Nguyen, B., Dhanda, S., Nickell, E., Siemborski, R., & Micco, J. (2017).** *Taming Google-scale continuous testing.* ICSE-SEIP 2017 (IEEE/ACM 39th International Conference on Software Engineering: Software Engineering in Practice).
  [DOI: 10.1109/ICSE-SEIP.2017.14](https://doi.org/10.1109/ICSE-SEIP.2017.14) · [IEEE](https://ieeexplore.ieee.org/abstract/document/7965447/) · [PDF (open access)](https://web.eecs.umich.edu/~weimerw/2021-481F/readings/googletest.pdf)
  Google ran into the same wall every large CI shop hits — full suites no longer fit in the feedback budget. The paper documents the heuristics they used; test-order borrows the same instincts (recency, churn, history) for projects too small to staff a CI team.

- **Machalica, M., Samylkin, A., Porth, M., & Chandra, S. (Facebook, 2019).** *Predictive Test Selection.* ICSE-SEIP 2019.
  [DOI: 10.1109/ICSE-SEIP.2019.00016](https://doi.org/10.1109/ICSE-SEIP.2019.00016) · [IEEE](https://ieeexplore.ieee.org/abstract/document/8804462/) · [arXiv: 1810.05286](https://arxiv.org/abs/1810.05286)
  PTS uses a lightweight gradient-boosted model on change features to predict failure probability per test. test-order's [optimizer](ARCHITECTURE.md) and the JaCoCo-coverage signal it consumes are inspired by this approach, scaled down to fit a single Maven/Gradle build with no remote service.

## Order-dependent test bugs

- **Lam, W., Oei, R., Shi, A., Marinov, D., & Xie, T. (2019).** *iDFlakies: A framework for detecting and partially classifying flaky tests.* ICST 2019 (IEEE International Conference on Software Testing, Verification and Validation).
  [DOI: 10.1109/ICST.2019.00038](https://doi.org/10.1109/ICST.2019.00038) · [IEEE](https://ieeexplore.ieee.org/abstract/document/8730188/)
  Framework for detecting and classifying order-dependent (OD) flaky tests. The empirical study showed the majority of OD failures in Java involve static field state — the reason test-order's [dependency detection](DETECT_DEPENDENCIES.md) tracks static field access at bytecode level.

- **Shi, A., Lam, W., Oei, R., Xie, T., & Marinov, D. (2019).** *iFixFlakies: A framework for automatically fixing order-dependent flaky tests.* ESEC/FSE 2019 (ACM Joint European Software Engineering Conference and Symposium on the Foundations of Software Engineering).
  [DOI: 10.1145/3338906.3338925](https://doi.org/10.1145/3338906.3338925) · [ACM](https://dl.acm.org/doi/abs/10.1145/3338906.3338925)
  Automated repair of OD flaky tests by identifying and resetting the polluting state. Companion to iDFlakies; together they cover detect → classify → fix.

- **Li, C., Khosravi, M. M., Lam, W., & Shi, A. (2023).** *Systematically producing test orders to detect order-dependent flaky tests.* ISSTA 2023 (ACM SIGSOFT International Symposium on Software Testing and Analysis).
  [DOI: 10.1145/3597926.3598083](https://doi.org/10.1145/3597926.3598083) · [ACM](https://dl.acm.org/doi/abs/10.1145/3597926.3598083)
  Introduces Tuscan-square combinatorial designs for pair-coverage over test orderings with far fewer runs than all-permutations. test-order implements the same idea in its `tuscan-systematic` mode (see [DETECT_DEPENDENCIES](DETECT_DEPENDENCIES.md)).

## How this maps to test-order

| Idea from research                       | Where it shows up in test-order                                                 |
| ---------------------------------------- | ------------------------------------------------------------------------------- |
| Failure-history weighting (Yoo/Harman)   | EMA scoring in [SCORING.md](SCORING.md)                                         |
| Coverage-based RTS (PTS)                 | JaCoCo signals + [affected-test selection](DETECT_DEPENDENCIES.md)              |
| Lightweight per-project ML               | Genetic optimizer in [ARCHITECTURE.md](ARCHITECTURE.md)                         |
| OD-bug detection (iDFlakies)             | `detect-dependencies` mode in [CLI_REFERENCE.mdx](CLI_REFERENCE.mdx)             |
| Tuscan-square pair-coverage (Li 2023)    | `tuscan-systematic` ordering in [DETECT_DEPENDENCIES](DETECT_DEPENDENCIES.md)  |
| Tiered execution (Memon/Google)          | `tiered-select` / `run-tier` goals in [CLI_REFERENCE.mdx](CLI_REFERENCE.mdx)     |

If you've come across a paper that should be on this list, please [open an issue](https://github.com/parttimenerd/test-order/issues) — the goal is for this page to grow with the project.
