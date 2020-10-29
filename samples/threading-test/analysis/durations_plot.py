import matplotlib.pyplot as plt
import sys
import pandas as pd


def main():
    try:
        filename = sys.argv[1]
        data = pd.read_csv(filename).sort_values("JOB_NUMBER")
        term_40 = data[data["TERM"] == 40]
        durations = term_40.DURATION / 1000
    except:
        print("Usage: durations_plot.py OUTPUT_FILE")
        print("OUTPUT_FILE: the output file generated by the fibonacci host")
        return

    fig, axes = plt.subplots(ncols=2)
    fig.set_size_inches(12, 6)  # 12 x 6 for double plots, 6x6 for single

    ewma(axes[0], durations)
    histogram(axes[1], durations)

    plt.show()


def ewma(axis, durations):
    alpha = 0.01
    ewma = durations.ewm(alpha=alpha).mean()

    axis.scatter(durations.index, durations, marker="x")
    axis.plot(ewma.index, ewma, color="orange")

    print(f"mean: {durations.mean()}")
    print(f"std: {durations.std()}")
    print(f"99th percentile: {durations.quantile(0.99)}")

    axis.set_xlabel("Call index")
    axis.set_ylabel("Duration / milliseconds")


def histogram(axis, durations):
    durations.hist(ax=axis, bins=1000)
    axis.set_xlabel("Duration / milliseconds")
    axis.set_ylabel("Count")
    axis.set_yscale("log")


main()
