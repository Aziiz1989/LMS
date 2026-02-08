#let data = json("data.json")

#set page(
  paper: "a4",
  margin: (top: 2cm, bottom: 2cm, left: 2cm, right: 2cm)
)
#set text(size: 10pt, lang: "ar", dir: rtl)
#set par(justify: true)

// Header
#align(center)[
  #text(size: 16pt, weight: "bold")[
    كشف حساب \ Account Statement
  ]
]

#v(1cm)

// Contract & Period Information
#grid(
  columns: (1fr, 1fr),
  row-gutter: 0.6cm,
  column-gutter: 1cm,

  [*رقم العقد / Contract:*], [#data.contract.external-id],
  [*العميل / Customer:*], [#data.contract.customer-name],
  [*بداية الفترة / Period Start:*], [#data.period-start],
  [*نهاية الفترة / Period End:*], [#data.period-end],
  [*الحالة / Status:*], [#data.contract.status],
)

#v(1cm)

// Summary Box
#block(
  fill: rgb("#f5f5f5"),
  inset: 1cm,
  radius: 4pt,
  width: 100%
)[
  #text(size: 12pt, weight: "bold")[ملخص الحساب / Account Summary]

  #v(0.5cm)

  #grid(
    columns: (1fr, 1fr),
    row-gutter: 0.5cm,
    column-gutter: 1cm,

    [مبلغ التمويل / Principal:], [#data.contract.principal SAR],
    [إجمالي المستحق / Total Outstanding:], [#data.total-outstanding SAR],
    [إجمالي المدفوع / Total Paid:], [#data.total-paid SAR],
    [الرصيد الدائن / Credit Balance:], [#data.credit-balance SAR],
    [التأمين المحتجز / Deposit Held:], [#data.deposit-held SAR],
  )
]

#v(1cm)

// Installment Schedule
#text(size: 12pt, weight: "bold")[جدول الأقساط / Installment Schedule]

#v(0.5cm)

#table(
  columns: (auto, auto, auto, auto, auto, auto),
  align: (center, center, center, center, center, center),
  stroke: 0.5pt,
  fill: (col, row) => if row == 0 { rgb("#e8e8e8") } else { white },

  [*#*], [*تاريخ الاستحقاق\ Due Date*], [*رأس المال\ Principal*], [*الربح\ Profit*], [*المدفوع\ Paid*], [*المتبقي\ Remaining*],

  ..for inst in data.installments {
    (
      [#inst.seq],
      [#inst.due-date],
      [#inst.principal-due],
      [#inst.profit-due],
      [#inst.total-paid],
      [#inst.outstanding],
    )
  }
)

#v(1cm)

// Fees
#if data.fees.len() > 0 [
  #text(size: 12pt, weight: "bold")[الرسوم / Fees]

  #v(0.5cm)

  #table(
    columns: (auto, auto, auto, auto, auto),
    align: (center, center, center, center, center),
    stroke: 0.5pt,
    fill: (col, row) => if row == 0 { rgb("#e8e8e8") } else { white },

    [*النوع / Type*], [*تاريخ الاستحقاق\ Due Date*], [*المبلغ\ Amount*], [*المدفوع\ Paid*], [*المتبقي\ Remaining*],

    ..for fee in data.fees {
      (
        [#fee.type],
        [#fee.due-date],
        [#fee.amount],
        [#fee.paid],
        [#fee.outstanding],
      )
    }
  )
]

#v(1cm)

// Totals
#align(right)[
  #table(
    columns: (auto, auto),
    align: (left, right),
    stroke: 0.75pt,
    fill: rgb("#f0f0f0"),

    [*إجمالي رأس المال المستحق / Total Principal Due*], [*#data.total-principal-due*],
    [*إجمالي الربح المستحق / Total Profit Due*], [*#data.total-profit-due*],
    [*إجمالي الرسوم المستحقة / Total Fees Due*], [*#data.total-fees-due*],
    table.hline(stroke: 1.5pt),
    [*الإجمالي المستحق / Total Outstanding*], [*#data.total-outstanding SAR*],
  )
]

#v(1cm)

// Footer
#align(center)[
  #text(size: 9pt)[
    تم إصدار هذا الكشف في #datetime.today().display()
    \ Statement generated on #datetime.today().display()
  ]
]
