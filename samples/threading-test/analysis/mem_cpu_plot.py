import pandas as pd
import matplotlib.pyplot as plt
import sys


def main():
    try:
        output_filename = sys.argv[1]
        df = pd.read_csv(output_filename)
    except:
        print("Usage: mem_cpu_plot.py LOG_FILE")
        print(
            "LOG_FILE: the path to the memory and cpu log file generated by mem_cpu_log.py"
        )
        return

    # fix dtypes
    df["DATETIME"] = pd.to_datetime(df["DATETIME"], format="%Y-%m-%d %H:%M:%S")
    df["MEM"] = pd.to_numeric(df["MEM"], errors="coerce")
    df["CPU"] = pd.to_numeric(df["CPU"], errors="coerce")
    df = df.dropna()

    fig, axes = plt.subplots(ncols=2)
    fig.set_size_inches(12, 6)

    df.plot(
        ax=axes[0],
        x="DATETIME",
        xlabel="Datetime",
        y="MEM",
        ylabel="Memory usage of conclave host / %",
    )
    df.plot(
        ax=axes[1],
        x="DATETIME",
        xlabel="Datetime",
        y="CPU",
        ylabel="CPU usage of conclave host / %",
    )
    plt.show()


main()