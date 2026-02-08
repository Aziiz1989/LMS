#let data = json("data.json")

#set page(
  paper: "a4",
  margin: (top: 2.5cm, bottom: 2.5cm, left: 2.5cm, right: 2.5cm)
)
#set text(size: 11pt, lang: "ar", dir: rtl)
#set par(justify: true)

// Header
#align(center)[
  #text(size: 18pt, weight: "bold")[
    عقد تمويل \ Financing Agreement
  ]
]

#v(1.5cm)

// Contract Information
#block(
  fill: rgb("#f8f8f8"),
  inset: 1cm,
  radius: 4pt,
  width: 100%
)[
  #grid(
    columns: (1fr, 1fr),
    row-gutter: 0.8cm,
    column-gutter: 1cm,

    [*رقم العقد / Contract Number:*], [#data.contract.at("contract/external-id")],
    [*تاريخ العقد / Contract Date:*], [#data.contract.at("contract/start-date")],
  )
]

#v(1.5cm)

// Financing Terms
#text(size: 14pt, weight: "bold")[شروط التمويل / Financing Terms]

#v(0.8cm)

#grid(
  columns: (1fr, 1fr),
  row-gutter: 0.8cm,
  column-gutter: 1.5cm,

  [*مبلغ التمويل / Principal Amount:*],
  [*#data.contract.at("contract/principal") SAR*],

  [*عدد الأقساط / Number of Installments:*],
  [#data.installments.len() installments],
)

#v(1.5cm)

// Installment Schedule
#text(size: 14pt, weight: "bold")[جدول السداد / Repayment Schedule]

#v(0.8cm)

#table(
  columns: (auto, auto, auto, auto, auto),
  align: (center, center, right, right, right),
  stroke: 0.75pt,
  fill: (col, row) => if row == 0 { rgb("#e0e0e0") } else if calc.rem(row, 2) == 0 { rgb("#f8f8f8") } else { white },

  [*رقم\ \#*], [*تاريخ الاستحقاق\ Due Date*], [*رأس المال\ Principal*], [*الربح\ Profit*], [*الإجمالي\ Total*],

  ..for inst in data.installments {
    (
      [#inst.at("installment/seq")],
      [#inst.at("installment/due-date")],
      [#inst.at("installment/principal-due")],
      [#inst.at("installment/profit-due")],
      [#calc.round((inst.at("installment/principal-due") + inst.at("installment/profit-due")), digits: 2)],
    )
  }
)

#v(1cm)

// Totals
#align(right)[
  #block(
    fill: rgb("#f0f0f0"),
    inset: 0.8cm,
    radius: 4pt,
  )[
    #let total-principal = data.installments.fold(0, (sum, inst) => sum + inst.at("installment/principal-due"))
    #let total-profit = data.installments.fold(0, (sum, inst) => sum + inst.at("installment/profit-due"))
    #let total-amount = total-principal + total-profit

    #grid(
      columns: (auto, auto),
      column-gutter: 2cm,
      row-gutter: 0.5cm,

      [*إجمالي رأس المال / Total Principal:*], [*#total-principal SAR*],
      [*إجمالي الربح / Total Profit:*], [*#total-profit SAR*],
      grid.hline(stroke: 1.5pt),
      [*المبلغ الإجمالي / Grand Total:*], [*#total-amount SAR*],
    )
  ]
]

#v(1.5cm)

// Fees
#if data.fees.len() > 0 [
  #text(size: 14pt, weight: "bold")[الرسوم / Fees]

  #v(0.8cm)

  #table(
    columns: (auto, auto, auto),
    align: (left, center, right),
    stroke: 0.75pt,
    fill: (col, row) => if row == 0 { rgb("#e0e0e0") } else { white },

    [*النوع / Type*], [*تاريخ الاستحقاق / Due Date*], [*المبلغ / Amount*],

    ..for fee in data.fees {
      (
        [#fee.at("fee/type")],
        [#fee.at("fee/due-date")],
        [#fee.at("fee/amount") SAR],
      )
    }
  )

  #v(0.5cm)

  #let total-fees = data.fees.fold(0, (sum, fee) => sum + fee.at("fee/amount"))
  #align(right)[
    *إجمالي الرسوم / Total Fees: #total-fees SAR*
  ]
]

#pagebreak()

// Banking Details (if available)
#if "contract/disbursement-iban" in data.contract [
  #text(size: 14pt, weight: "bold")[التفاصيل المصرفية / Banking Details]

  #v(0.8cm)

  #grid(
    columns: (1fr, 1fr),
    row-gutter: 0.8cm,
    column-gutter: 1.5cm,

    [*حساب الصرف / Disbursement IBAN:*],
    [#data.contract.at("contract/disbursement-iban", default: "N/A")],

    [*البنك / Bank:*],
    [#data.contract.at("contract/disbursement-bank", default: "N/A")],
  )

  #v(2cm)
]

// Signatures
#text(size: 14pt, weight: "bold")[التوقيعات / Signatures]

#v(1.5cm)

#grid(
  columns: (1fr, 1fr),
  column-gutter: 2cm,
  row-gutter: 3cm,

  [
    #align(center)[
      #line(length: 80%)
      #v(0.3cm)
      الطرف الأول / First Party
    ]
  ],
  [
    #align(center)[
      #line(length: 80%)
      #v(0.3cm)
      الطرف الثاني / Second Party
    ]
  ],
)

#v(2cm)

#align(center)[
  #text(size: 9pt)[
    هذا العقد تم إنشاؤه بتاريخ #data.contract.at("contract/start-date")
    \ This agreement was created on #data.contract.at("contract/start-date")
  ]
]
