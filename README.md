<p align="center">
  <img src="./Logo.png" alt="Ledgedroid icon" width="256">
</p>

<h1 align="center">Ledgedroid</h1>

<p align="center">
  A small Android app for <a href="https://hledger.org">hledger</a> journals on your phone.
</p>

<p align="center">
  <a href="https://hledger.org/sponsor.html"><img src="https://img.shields.io/badge/sponsor-hledger-ea4aaa?style=flat-square&logo=githubsponsors&logoColor=white" alt="Sponsor hledger"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+">
  <a href="https://github.com/hnvy/ledgedroid/releases"><img src="https://img.shields.io/github/downloads/hnvy/ledgedroid/total?style=flat-square" alt="Downloads"></a>
</p>


## NOTE TO USER!
- Ledgedroid is built on hledger, which is free (beer and speech) software maintained
by Simon et al. If Ledgedroid is useful to you, please consider
[sponsoring hledger](https://hledger.org/sponsor.html).
- The app icon was drawn using the beautiful [Aseprite](https://www.aseprite.org/). Buy it and you won't regret it!

## Quick glance

- Balance, balance sheet, income statement and budget reports, by
  period, with trend charts
- hledger queries like `desc:`, `amt:>100`, `tag:trip` and `-M -H`,
  typed straight into the filter
- A full entry form, an `hledger add`-style quick add, templates, a
  home screen widget and a quick settings tile
- A colour for each top-level account, shaded by depth
- Pinned accounts that stay at the top, and hidden ones that stay out
  of the way

## How to use it

- The main page shows all accounts and their respective balances. It's
  basically `hledger balance`.  For the income statement, balance
  sheet or budget, type `is` (`--incomestatement`), `bs`
  (`--balancesheet`) or `--budget` in the filter (see below). The
  starred **Income statement** chip shows `is` with one tap; tap it
  again to go back.
- There are "chips" at the top which allow you to sort and change the
  views as you please.
- The **Options** chips lets you do the following: apply auto postings
  (`--auto`), tree or list, balance at end of period (`-H`), show zero
  balances (`-E`), sort by amount (`-S`), convert to cost (`-B`), flip
  signs (`--invert`).
- There is a filter icon at the top (looks like a funnel). If you
  click it, a text area will appears allowing you to enter some common
  hledger commands (like `--incomestatement` or `is`, `--balancesheet`
  or `bs`, `--budget`). Tap ⓘ in it for a full list.
- **Saved reports** (menu, or the starred chips above the report): save
  the current report, with its period, options and search, to come
  back to with one tap. The income statement comes as one.
- The magnifying glass is a search function. It searches every
  transaction, newest first. Plain words match descriptions, accounts,
  comments and amounts ("cafe", "4.50"); all the filter terms above
  work too.
- **Hiding accounts**: long-press › Hide removes an account and its
  subaccounts from every report and total (like `not:`). The bar
  showing the hidden count has **Show for now** / **Hide again**. **⋮ ›
  Hidden accounts…** manages the list: a switch and × per account, a
  switch for all, Unhide all (also in ⋮), a switch to turn off the
  bar, and **Keep hidden accounts in totals** (hides rows only; parents,
  totals and transaction lists still include them). The journal is NOT
  changed.
- **Pinning accounts**: long-press › **Pin to the top** lists an account
  under a star at the top of every report, with its total including
  subaccounts (per period, and with goals in the budget). In the tree
  it gets a star and a gold edge. Balance sheets and income statements
  only show pinned accounts that belong to them. **Hide pinned
  accounts at 0** (⋮ by the Pinned heading) omits those with nothing
  to show, and the heading counts them.
- Tap an account for its transactions with a running balance, and a
  transaction for its postings, comments, tags and journal text.
    - When an account has money going both ways in the period, buttons
      show one side, named by type: **In /
  Out** (assets), **Charges / Payments** (liabilities), **Spending /
  Refunds** (expenses), **Received / Reversed** (revenues), **Positive
  / Negative** (others), with counts. The total becomes that side's
  and the equivalent filter (`amt:<0`/`amt:>0`) is shown.
    - **Advanced filters** narrow the list by description or comment
      words, amount (at least/at most), status,
  other account, tag, currency and dates, with suggestions from the
  account's transactions. All must match; the total follows; **Clear**
  removes them. Sides and filters persist when you open a transaction
  and come back.

## Adding, editing and deleting

Tap **+** to add a transaction. Fields autocomplete from the journal;
one amount can be left empty, and amounts can be sums (`12+8`) and
other basic arithmetics. It won't save until it balances. **✓✓** saves
and starts another.

**Quick add** (Settings) works like `hledger add`, one prompt at a
time, with Tab to cycle suggestions and Enter for defaults. Dates can
be `11-02`, `yesterday` or `-3`.

Transactions are appended in the journal's style; ⋮ › **Align
amounts** or **Amount spacing…** changes the layout, and **Fields
shown…** hides unused fields.

Tap a transaction to **Edit**, **Delete** or mark it **Pending** /
**Cleared**. Transactions with features the form can't represent
(codes, costs, assertions, virtual postings…) can be marked but not
edited. Changes are refused if the file changed elsewhere, and
**Undo** reverts the last one. Every write is verified, and the
original is restored if it fails.

**Templates** prefill the form; save from the form's ⋮, manage in the
main menu. Open the form from the widget, quick settings tile or app
shortcut.

## Extra bits

There are widgets which display the "add transaction" templates. There
are also Quick tiles to be added to the notification area.

## Backups

Choose the journal's folder in **Settings**. Options: regular backups, a
backup before each change (on by default), skip if unchanged, how many
to keep, and **Back up now**. Restore by copying a `.bak` file over the
journal.

## Journal support Supported: periodic rules (`~`) and auto posting
rules (`=`, with `*N` and `%account`, applied in order as in hledger);
transaction status, codes, secondary dates and comments; one inferred
amount per transaction, `@`/`@@` costs, `= == =* ==*` assertions and
assignments, `(virtual)` and `[balanced virtual]` postings; either
decimal mark, digit groups, left/right and quoted commodity symbols;
`account` (with `type:`), `commodity` (with `format`), `payee`,
`decimal-mark`, `D`, `Y`/`year`, `alias`, `apply account` and
`comment` directives. Comment lines under a posting belong to it.

Not supported: `include` (skipped with a note), market prices and
valuation (`-V`, `-X`), lots, timeclock and timedot. Query terms:
account patterns, `acct:`, `desc:`, `payee:`, `cur:`, `status:`,
`date:`, `amt:`, `tag:`, `depth:`, `not:` (not `date2:`, `code:`,
`real:`, `expr:` and the like).
